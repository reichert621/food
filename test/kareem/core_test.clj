(ns kareem.core-test
  (:require [clojure.test :refer :all]
            [kareem.core :refer :all]))

(def test-request
  {:body
   {:entry
    [{:id "2249915435035344",
      :time 1529907625651,
      :messaging
            [{:sender {:id "1693729047342220"},
              :recipient {:id "2249915435035344"},
              :timestamp 1529907625320,
              :message {:mid "mid.$cAAf-SXy4qRtqZt8FaFkNZm9WRDhp", :seq 448437, :text "wat"}}]}]}})

(deftest token-test
  (testing "has the correct token"
    (is (= expected-token "fitallday"))))

(deftest flatten-messages-test
  (testing "flattening the request"
    (let [expected (first (flatten-messages test-request))
          actual {:sender {:id "1693729047342220"},
                  :recipient {:id "2249915435035344"},
                  :timestamp 1529907625320,
                  :message {:mid "mid.$cAAf-SXy4qRtqZt8FaFkNZm9WRDhp", :seq 448437, :text "wat"}}]
      (is (= expected actual)))))

(deftest format-timestamp-test
  (testing "formats the timestamp to YYYY-MM-DD"
    (is (= (format-timestamp 1529907625320) "2018-06-25"))))