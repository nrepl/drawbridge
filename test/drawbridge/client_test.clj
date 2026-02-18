(ns drawbridge.client-test
  (:require [clojure.test :refer [deftest is use-fixtures testing]]
            [compojure.core :refer [ANY defroutes]]
            [compojure.handler :as handler]
            [drawbridge.core :as drawbridge]
            [drawbridge.client]
            [nrepl.core :as nrepl]
            [ring.adapter.jetty :as jetty]))

(let [nrepl-handler (drawbridge/ring-handler)]
  (defroutes app
    (ANY "/repl" request (nrepl-handler request))))

(defn server-fixture
  [f]
  (let [server (jetty/run-jetty (handler/api #'app) {:port 12345 :join? false})]
    (f)
    (.stop server)))

(use-fixtures :once server-fixture)

(defn without-unstable-keys
  [res]
  (map #(dissoc % :id :session) res))

(defn send-message
  [client message]
  (-> client
      (nrepl/message message)
      without-unstable-keys
      first))

(deftest sending-messages
  (with-open [conn (nrepl/url-connect "http://localhost:12345/repl")]
    (let [client (nrepl/client conn 1000)]
      (testing "Evaluating a valid form returns the value"
        (is (= {:value "3"
                :ns    "user"}
               (send-message client {:op "eval" :code "(+ 1 2)"}))))

      (testing "Evaluating an invalid form returns an error"
        (is (re-find #"Syntax error reading source at \(REPL:\d+:\d+\)\.\nEOF while reading"
                    (:err (send-message client {:op "eval" :code "(+ 1 2"})))))

      (testing "Evaluating a form that throws returns an error"
        (is (.startsWith
              (:err (send-message client {:op "eval" :code "(throw (ex-info nil {:foo :bar}))"}))
              "Execution error (ExceptionInfo) at user/eval"))))))
