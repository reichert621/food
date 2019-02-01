(ns kareem.core
  (:require [ring.adapter.jetty :as jetty]
            [compojure.core :refer [defroutes ANY GET POST PUT DELETE]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.util.response :refer [response]]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [clojure.java.io :as io]
            [environ.core :refer [env]]
            [aleph.http :as http]
            [hashids.core :as hashids])
  (:import (java.lang Long)
           (com.google.firebase FirebaseApp)
           (com.google.firebase FirebaseOptions$Builder)
           (com.google.auth.oauth2 ServiceAccountCredentials)
           (java.io ByteArrayOutputStream)
           (com.google.firebase.database FirebaseDatabase ValueEventListener)
           (com.google.firebase.cloud StorageClient)
           (com.google.cloud.storage BlobInfo Storage$BlobTargetOption Acl Acl$User Acl$Role)
           (java.util UUID ArrayList)
           (java.net URL)
           (org.apache.http.impl.client HttpClients LaxRedirectStrategy)
           (org.apache.http.client.methods HttpGet)))



(defmacro nil-throws [x & [msg]]
  `(let [x# ~x]
     (when (nil? x#)
       (throw (ex-info (format "%s was nil%s"
                               '~x
                               (let [msg# ~msg]
                                 (if (string? msg#)
                                   (str " -- " msg#)
                                   "")))
                       {:form '~x})))
     x#))

(defn enforce-env! [k]
  (nil-throws (System/getenv k) (str "k=" k)))

(defn expected-token []
  (enforce-env! "MESSENGER_VERIFY_TOKEN"))

(defn db-url []
  (enforce-env! "FIREBASE_DB_URL"))

(defn default-bucket []
  (enforce-env! "FIREBASE_DEFAULT_BUCKET"))

(defn firebase-creds []
  (ServiceAccountCredentials/fromPkcs8
   (enforce-env! "FIREBASE_CLIENT_ID")
   (enforce-env! "FIREBASE_CLIENT_EMAIL")
   (enforce-env! "FIREBASE_PRIVATE_KEY")
   (enforce-env! "FIREBASE_PRIVATE_KEY_ID")
   []))

(defn hashids-opts [] {:salt (expected-token)})

(defn num->hash [x]
  (hashids/encode hashids-opts x))

(defn hash->num [x]
  (nil-throws (first (hashids/decode hashids-opts x)) (str "x=" x)))

(def type->ext
  {"audio" ".mp4"
   "video" ".mp4"
   "image" ".jpg"})

(defn uuid [] (str (UUID/randomUUID)))

(defn get-storage []
  (-> (StorageClient/getInstance)
      (.bucket (default-bucket))
      .getStorage))

(defn get-roles []
  (-> (Acl$User/ofAllUsers)
      (Acl/of Acl$Role/READER)))

(defn get-acls []
  (doto (ArrayList.) (.add (get-roles))))

(defn build-blob-info [filename]
  (-> (BlobInfo/newBuilder (default-bucket) filename)
      (.setAcl (get-acls))
      .build))

(defn uri->stream [uri]
  (let [client (-> (HttpClients/custom)
                   (.setRedirectStrategy
                     (LaxRedirectStrategy.))
                   .build)
        get-req (HttpGet. (.toURI (URL. uri)))
        res (.execute client get-req)]
    (with-open [in (-> res
                       .getEntity
                       .getContent)
                out (ByteArrayOutputStream.)]
      (io/copy in out)
      out)))

(defn upload-file! [uri filename]
  (let [storage (get-storage)
        blob-info (build-blob-info filename)
        stream (uri->stream uri)]
    (.create
      storage
      blob-info
      (.toByteArray stream)
      (into-array Storage$BlobTargetOption []))))

(defn get-attachment-filename [{:keys [type]}]
  (str (uuid) (type->ext type)))

(defn update-attachment! [attachment]
  (let [uri (-> attachment :payload :url)
        filename (get-attachment-filename attachment)
        blob (upload-file! uri filename)
        firebase-uri (.getMediaLink blob)]
    (assoc-in attachment [:payload :firebase-uri] firebase-uri)))

(defn update-attachments! [message]
  (update-in
    message
    [:message :attachments]
    (fn [attachments] (map update-attachment! attachments))))

(defn pong [request]
  (response {:message "Pong"}))

(defn initialize-firebase []
  (let [options (-> (FirebaseOptions$Builder.)
                    (.setCredentials (firebase-creds))
                    (.setDatabaseUrl (db-url))
                    .build)]
    (FirebaseApp/initializeApp options)))

(defn get-user-messages [id]
  (let [p (promise)
        db (FirebaseDatabase/getInstance)
        ref (.getReference db (str "users/" id))
        event-listener (reify ValueEventListener
                         (onDataChange [_ s]
                           (deliver p (into {} (.getValue s))))
                         (onCancelled [_ err]
                           (throw (ex-info "Failed to get user messages" {:firebase-err err}))))]
    (.addListenerForSingleValueEvent ref event-listener)
    p))

(defn get-user [{{:keys [id]} :params}]
  {:status 200
   :headers {"content-type" "application/json"
             "Access-Control-Allow-Origin" "*"}
   :body @(get-user-messages (hash->num id))})

(defn get-message [request]
  (let [token (get-in request [:query-params "hub.verify_token"])
        challenge (get-in request [:query-params "hub.challenge"])]
    (if (= token (expected-token))
      (response challenge))))

(defn flatten-messages [request]
  (some->>
    request
    :body
    :entry
    (mapcat :messaging)))

(defn get-firebase-path [{:keys [timestamp sender] :as message}]
  (let [{:keys [id]} sender]
    (string/join "/" ["users" id timestamp])))

(defn get-firebase-ref [path]
  (-> (FirebaseDatabase/getInstance)
      .getReference
      (.child path)))

;; TODO(stopachka) rename to event
(defn save-message! [message]
  (let [path (get-firebase-path message)
        ref (get-firebase-ref path)]
    @(.setValueAsync ref (walk/stringify-keys message))))

(defn save-messages! [messages]
  (for [message messages]
    (save-message! message)))

(defn send-message! [message-event]
  @(http/post
    "https://graph.facebook.com/v2.6/me/messages"
    {:query-params {:access_token (enforce-env! "MESSENGER_TOKEN")}
     :body (cheshire.core/encode message-event)
     :headers {:content-type "application/json"}}))

(defn history-uri [{:keys [id]}]
  (str "https://hipluot.com/u/" (num->hash (Long. id))))

(defn history-message [{:keys [sender]}]
  {:recipient sender
   :message {:text (history-uri sender)}})

(defn get-started-message [{:keys [sender]}]
  {:recipient sender
   :message {:text "Welcome! Send text, audio, videos, etc."}})

(defn get-random-emoji []
  (rand-nth ["👍" "👌" "✌️" "👊" "✊" "🤖"]))

(defn response-message [sender]
  {:recipient sender
   :message {:text (get-random-emoji)}})

(defn handle-postback! [{:keys [postback] :as postback-event}]
  (let [{:keys [payload]} postback]
    (case payload
      "HISTORY" (send-message! (history-message postback-event))
      "GET_STARTED" (send-message! (get-started-message postback-event)))))

(defn respond-to-messages! [messages]
  (->> messages
       (map :sender)
       set
       (map response-message)
       (map send-message!)
       seq))

(defn post-message [request]
  (let [groups (->> request
                      flatten-messages
                      (group-by (comp boolean :postback)))
          msg-events (get groups false)
          postback-events (get groups true)]
      (->> postback-events
           (map handle-postback!)
           seq)
      (->> msg-events
           (map update-attachments!)
           save-messages!
           seq)
      (respond-to-messages! msg-events))
  (response {}))

;; ------------------
;; sms

(defn parse-int [s]
  (Long. (re-find #"\d+" s)))

(defn xml-response [body]
  {:status 200
   :headers {"content-type" "application/xml"}
   :body (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" body)})

(defn text-twiml [message]
  (format "<Response>
            <Message>
              <Body>
              %s
              </Body>
            </Message>
          </Response>"
          message))

(defn ->atts [params]
  (let [num-media (parse-int (:NumMedia params))]
    (->> num-media
         range
         (keep (fn [i]
                 (let [content-type-k (keyword (str "MediaContentType" i))
                       url-k (keyword (str "MediaUrl" i))
                       content-type (content-type-k params)]
                   ;; TODO(stopachka)
                   ;; remove "type", and use content-type in the ui
                   ;; this will allow us to be more specific about extensions
                   (when (= content-type "image/jpeg")
                     {:type "image"
                      :content-type content-type
                      :payload {:url (url-k params)}})))))))

(defn ->message [params]
  (let [text (:Body params)
        from (:From params)
        atts (->atts params)]
    {:sender {:id (parse-int from)
              :from from}
     :message {:text text
               :attachments atts}}))

(defn parse-intent [{:keys [text attachments]}]
  (let [text (-> text str string/lower-case string/trim)]
    (cond
      (seq attachments)
      ::log

      (string/includes? text "history")
      ::history

      :else ::log)))

(defn post-sms [{:keys [params]}]
  (let [text-res #(xml-response (text-twiml %))
        {:keys [sender message] :as evt} (->message params)
        intent (parse-intent message)]
    (condp = intent
      ::history
      (text-res (history-uri sender))

      ::log
      (do
        ;; TODO(stopachka)
        ;; What happens if this blocks for too long or fails?
        ;; i.e maybe we should haveP timeouts / overall try catch
        (save-message! (update-attachments! evt))
        (text-res (get-random-emoji)))

      (text-res "An unexpected error occured. Give us a ping :}"))))

(defroutes routes
           (GET "/ping" [] pong)
           (GET "/message" [] get-message)
           (POST "/message" [] post-message)
           (POST "/sms" [] post-sms)
           (GET "/users/:id" [] get-user))

;; ------------------
;; main

(defn -main
  [& [port]]
  (let [port (Integer. (or port (enforce-env! "PORT") 8000))
        app (-> routes
                wrap-keyword-params
                wrap-params
                (wrap-json-body {:keywords? true})
                wrap-json-response)]
    (initialize-firebase)
    (jetty/run-jetty app {:port port})))