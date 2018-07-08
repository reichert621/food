(defproject kareem "0.1.0-SNAPSHOT"
  :description "Track your nutrition"
  :url "https://github.com/reichert621/food"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repl-options {:port 4001}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [ring "1.7.0-RC1"]
                 [compojure "1.6.1"]
                 [ring/ring-json "0.4.0"]
                 [matchbox "0.0.9"]
                 [com.google.firebase/firebase-admin "6.2.0"]
                 [clj-time "0.14.4"]
                 [environ "1.1.0"]
                 [aleph "0.4.6"]]
  :min-lein-version "2.8.0"
  :main kareem.core)
