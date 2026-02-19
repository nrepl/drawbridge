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

(def ^:dynamic *port* nil)

(defn server-fixture
  [f]
  (let [server (jetty/run-jetty (handler/api #'app) {:port 0 :join? false})
        port (.getLocalPort (first (.getConnectors server)))]
    (try
      (binding [*port* port]
        (f))
      (finally
        (.stop server)))))

(use-fixtures :once server-fixture)

(defn- repl-url []
  (str "http://localhost:" *port* "/repl"))

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
  (with-open [conn (nrepl/url-connect (repl-url))]
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

(deftest sequential-evals-preserve-state
  (with-open [conn (nrepl/url-connect (repl-url))]
    (let [client (nrepl/client conn 1000)]
      (send-message client {:op "eval" :code "(def x 42)"})
      (is (= "42" (:value (send-message client {:op "eval" :code "x"})))))))

(deftest stdout-output
  (with-open [conn (nrepl/url-connect (repl-url))]
    (let [client (nrepl/client conn 1000)
          messages (without-unstable-keys
                    (nrepl/message client {:op "eval" :code "(println \"hello\")"}))]
      (is (some #(= "hello\n" (:out %)) messages)))))

(deftest describe-op
  (with-open [conn (nrepl/url-connect (repl-url))]
    (let [client (nrepl/client conn 1000)
          resp (send-message client {:op "describe"})]
      (is (some? (:ops resp))))))

(deftest concurrent-clients-get-own-responses
  (with-open [conn-a (nrepl/url-connect (repl-url))
              conn-b (nrepl/url-connect (repl-url))]
    (let [client-a (nrepl/client conn-a 1000)
          client-b (nrepl/client conn-b 1000)]
      (is (= "3" (:value (send-message client-a {:op "eval" :code "(+ 1 2)"}))))
      (is (= "30" (:value (send-message client-b {:op "eval" :code "(+ 10 20)"})))))))

