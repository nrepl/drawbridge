(ns drawbridge.bridge-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [compojure.core :refer [ANY defroutes]]
            [drawbridge.bridge :as bridge]
            [drawbridge.core :as drawbridge]
            [nrepl.core :as nrepl]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.nested-params :refer [wrap-nested-params]]
            [ring.middleware.params :refer [wrap-params]]))

(let [nrepl-handler (drawbridge/ring-handler)]
  (defroutes app
    (ANY "/repl" request (nrepl-handler request))))

(def ^:dynamic *bridge-port* nil)
(def ^:dynamic *repl-url* nil)

(defn bridge-fixture
  [f]
  (let [server (jetty/run-jetty (-> #'app wrap-keyword-params wrap-nested-params wrap-params)
                                {:port 0 :join? false})
        http-port (.getLocalPort (first (.getConnectors server)))
        url (str "http://localhost:" http-port "/repl")
        bridge (bridge/start-bridge {:url url})]
    (try
      (binding [*bridge-port* (:port bridge)
                *repl-url* url]
        (f))
      (finally
        (bridge/stop-bridge bridge)
        (.stop server)))))

(use-fixtures :once bridge-fixture)

(defn- send-message
  "Send a message over a client and return the first response without
  the per-run :id/:session keys."
  [client message]
  (-> (nrepl/message client message)
      first
      (dissoc :id :session)))

;; Connecting with nrepl/connect uses the plain bencode socket
;; transport -- the same path editors and rebel-readline take.
(deftest socket-client-eval
  (with-open [conn (nrepl/connect :port *bridge-port*)]
    (let [client (nrepl/client conn 5000)]
      (testing "a plain socket nREPL client can eval through the bridge"
        (is (= {:value "3" :ns "user"}
               (send-message client {:op "eval" :code "(+ 1 2)"}))))

      (testing "stdout is relayed back"
        (let [messages (nrepl/message client {:op "eval" :code "(println \"hello\")"})]
          (is (some #(= "hello\n" (:out %)) messages))))

      (testing "state is preserved across evals on the same connection"
        (send-message client {:op "eval" :code "(def bridged 41)"})
        (is (= "42" (:value (send-message client {:op "eval" :code "(inc bridged)"}))))))))

(deftest socket-client-describe
  (with-open [conn (nrepl/connect :port *bridge-port*)]
    (let [client (nrepl/client conn 5000)]
      (testing "describe lists ops, as editors expect during handshake"
        (is (some? (:ops (send-message client {:op "describe"}))))))))

(deftest connection-isolation
  (testing "concurrent local connections get separate remote sessions"
    (with-open [conn-a (nrepl/connect :port *bridge-port*)
                conn-b (nrepl/connect :port *bridge-port*)]
      (let [client-a (nrepl/client conn-a 5000)
            client-b (nrepl/client conn-b 5000)]
        (send-message client-a {:op "eval" :code "(def isolated :a)"})
        (is (= "3" (:value (send-message client-a {:op "eval" :code "(+ 1 2)"}))))
        (is (= "30" (:value (send-message client-b {:op "eval" :code "(+ 10 20)"}))))))))

(deftest http-headers-are-forwarded
  (testing "bridge sends configured headers on every request"
    (let [seen (atom #{})
          nrepl-handler (drawbridge/ring-handler)
          handler (-> (fn [request]
                        (when-let [auth (get-in request [:headers "authorization"])]
                          (swap! seen conj auth))
                        (nrepl-handler request))
                      wrap-keyword-params
                      wrap-nested-params
                      wrap-params)
          server (jetty/run-jetty handler {:port 0 :join? false})
          port (.getLocalPort (first (.getConnectors server)))
          bridge (bridge/start-bridge {:url (str "http://localhost:" port "/repl")
                                       :http-headers {"Authorization" "Bearer s3cret"}})]
      (try
        (with-open [conn (nrepl/connect :port (:port bridge))]
          (let [client (nrepl/client conn 5000)]
            (is (= "3" (:value (send-message client {:op "eval" :code "(+ 1 2)"}))))
            (is (= #{"Bearer s3cret"} @seen))))
        (finally
          (bridge/stop-bridge bridge)
          (.stop server))))))

(deftest stop-closes-listener
  (testing "after stop-bridge the port no longer accepts connections"
    (let [bridge (bridge/start-bridge {:url *repl-url*})
          port (:port bridge)]
      (bridge/stop-bridge bridge)
      (is (thrown? java.net.ConnectException
                   (.close (java.net.Socket. "127.0.0.1" (int port))))))))

(deftest start-requires-url
  (is (thrown? IllegalArgumentException (bridge/start-bridge {}))))
