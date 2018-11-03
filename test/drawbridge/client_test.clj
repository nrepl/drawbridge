(ns drawbridge.client-test
  (:require [clojure.test :refer [deftest is use-fixtures testing]]
            [compojure.core :refer [ANY defroutes]]
            [compojure.handler :as handler]
            [drawbridge.core :as drawbridge]
            [drawbridge.client]
            [nrepl.core :as nrepl]
            [org.httpkit.server :as server]))

(let [nrepl-handler (drawbridge/ring-handler)]
  (defroutes app
    (ANY "/repl" request (nrepl-handler request))))

(defn server-fixture
  [f]
  (let [stop-fn (server/run-server (handler/api #'app) {:port 12345})]
    (f)
    (stop-fn)))

(use-fixtures :once server-fixture)

(defn send-message
  [client message]
  (-> client
      (nrepl/message message)
      nrepl/response-values
      last))

(deftest sending-messages
  (with-open [conn (nrepl/url-connect "http://localhost:12345/repl")]
    (let [client (nrepl/client conn 1000)]
      (testing "Evaluating valid form returns the result"
        (is (= 3 (send-message client {:op "eval" :code "(+ 1 2)"}))))

      (testing "Evaluating invalid form returns nil"
        (is (nil? (send-message client {:op "eval" :code "(+ 1 2"}))))

      (testing "Evaluating a form that throws returns nil"
        (is (nil? (send-message client {:op "eval" :code "(throw (ex-info nil {:foo :bar}))"})))))))
