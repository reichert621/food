(ns kareem.core
  (:require [ring.adapter.jetty :as jetty]
            [compojure.core :refer [defroutes ANY GET POST PUT DELETE]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.util.response :refer [response]]
            [clj-time.core :as t-core]
            [clj-time.coerce :as t-coerce]
            [clj-time.format :as t-fmt]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [clojure.java.io :as io]
            [environ.core :refer [env]]
            [aleph.http :as http])
  (:import (com.google.firebase FirebaseApp)
           (com.google.firebase FirebaseOptions$Builder)
           (com.google.auth.oauth2 GoogleCredentials UserCredentials ServiceAccountCredentials)
           (java.io FileInputStream ByteArrayOutputStream)
           (com.google.firebase.database FirebaseDatabase)
           (com.google.firebase.cloud StorageClient)
           (com.google.cloud.storage BlobInfo Storage$BlobTargetOption Acl Acl$User Acl$Role)
           (java.util UUID Arrays ArrayList HashMap)
           (com.google.api.client.googleapis.auth.oauth2 OAuth2Utils)))


(defn enforce-env! [k]
  (if-let [v (System/getenv k)]
    v
    (throw (IllegalArgumentException. (str k " does not exist")))))

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

(def type->ext
  {"audio" ".mp4"
   "video" ".mp4"
   "image" ".jpg"})

(defn uuid [] (str (UUID/randomUUID)))

(defn get-storage []
  (-> (StorageClient/getInstance)
      (.bucket default-bucket)
      .getStorage))

(defn get-roles []
  (-> (Acl$User/ofAllUsers)
      (Acl/of Acl$Role/READER)))

(defn get-acls []
  (doto (ArrayList.) (.add (get-roles))))

(defn build-blob-info [filename]
  (-> (BlobInfo/newBuilder default-bucket filename)
      (.setAcl (get-acls))
      .build))

(defn uri->stream [uri]
  (with-open [in (io/input-stream uri)
              out (ByteArrayOutputStream.)]
    (io/copy in out)
    out))

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
  (str "https://kareem-2fdc3.firebaseapp.com/" id))

(defn history-message [{:keys [sender]}]
  {:recipient sender
   :message {:text (history-uri sender)}})

(defn get-started-message [{:keys [sender]}]
  {:recipient sender
   :message {:text "Welcome! Send text, audio, videos, etc."}})

(defn handle-postback! [{:keys [postback] :as postback-event}]
  (let [{:keys [payload]} postback]
    (case payload
      "HISTORY" (send-message! (history-message postback-event))
      "GET_STARTED" (send-message! (get-started-message postback-event)))))

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
           seq))
  (response {}))

(defroutes routes
           (GET "/ping" [] pong)
           (GET "/message" [] get-message)
           (POST "/message" [] post-message))

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