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
            [clojure.java.io :as io])
  (:import (com.google.firebase FirebaseApp)
           (com.google.firebase FirebaseOptions$Builder)
           (com.google.auth.oauth2 GoogleCredentials)
           (java.io FileInputStream ByteArrayOutputStream)
           (com.google.firebase.database FirebaseDatabase)
           (com.google.firebase.cloud StorageClient)
           (com.google.cloud.storage BlobInfo Storage$BlobTargetOption Acl Acl$User Acl$Role)
           (java.util UUID Arrays ArrayList)))

(def expected-token "fitallday")

(def db-url "https://kareem-2fdc3.firebaseio.com")

(def default-bucket "kareem-2fdc3.appspot.com")

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
  (let [stream (FileInputStream. "firebase-creds.json")
        creds (GoogleCredentials/fromStream stream)
        options (-> (FirebaseOptions$Builder.)
                    (.setCredentials creds)
                    (.setDatabaseUrl db-url)
                    .build)]
    (FirebaseApp/initializeApp options)))

(defn get-message [request]
  (let [token (get-in request [:query-params "hub.verify_token"])
        challenge (get-in request [:query-params "hub.challenge"])]
    (if (= token expected-token)
      (response challenge))))

(defn flatten-messages [request]
  (some->>
    request
    :body
    :entry
    (mapcat :messaging)))

(defn format-timestamp [timestamp]
  (->> timestamp
       t-coerce/from-long
       (t-fmt/unparse (t-fmt/formatter :year-month-day))))

(defn get-firebase-path [{:keys [timestamp sender] :as message}]
  (let [{:keys [id]} sender
        date (format-timestamp timestamp)]
    (string/join "/" ["users" id date timestamp])))

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

(defn post-message [request]
  (->> request
       flatten-messages
       (map update-attachments!)
       save-messages!
       seq)
  (response {}))

(defroutes routes
           (GET "/ping" [] pong)
           (GET "/message" [] get-message)
           (POST "/message" [] post-message))

(defn -main
  [& [port]]
  (let [port (Integer. (or port 8000))
        app (-> routes
                wrap-keyword-params
                wrap-params
                (wrap-json-body {:keywords? true})
                wrap-json-response)]
    (initialize-firebase)
    (jetty/run-jetty app {:port port})))