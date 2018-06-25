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
            [clojure.walk :as walk])
  (:import (com.google.firebase FirebaseApp)
           (com.google.firebase FirebaseOptions$Builder)
           (com.google.auth.oauth2 GoogleCredentials)
           (java.io FileInputStream)
           (com.google.firebase.database FirebaseDatabase)))

(def expected-token "fitallday")

(def db-url "https://kareem-2fdc3.firebaseio.com")

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
  (-> request
      flatten-messages
      save-messages!
      seq)
  (response {}))

(defroutes routes
           (GET "/ping" [] pong)
           (GET "/message" [] get-message)
           (POST "/message" [] post-message))

(defn -main
  [port-number]
  (let [app (-> routes
                wrap-keyword-params
                wrap-params
                (wrap-json-body {:keywords? true})
                wrap-json-response)]
    (initialize-firebase)
    (jetty/run-jetty app
                     {:port (Integer. port-number)})))