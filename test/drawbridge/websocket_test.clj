(ns drawbridge.websocket-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [drawbridge.auth :as auth]
            [drawbridge.bridge :as bridge]
            [drawbridge.websocket :as websocket]
            [drawbridge.websocket-client]
            [nrepl.core :as nrepl]
            [ring.adapter.jetty :as jetty]))

(def ^:dynamic *port* nil)

(defn server-fixture
  [f]
  (let [server (jetty/run-jetty (websocket/ring-handler)
                                {:port 0 :join? false})]
    (try
      (binding [*port* (.getLocalPort (first (.getConnectors server)))]
        (f))
      (finally
        (.stop server)))))

(use-fixtures :once server-fixture)

(defn- ws-url [] (str "ws://localhost:" *port* "/"))

(defn- send-message
  [client message]
  (-> (nrepl/message client message)
      first
      (dissoc :id :session)))

(deftest websocket-eval
  ;; url-connect dispatches on the ws scheme thanks to the
  ;; registration in drawbridge.websocket-client.
  (with-open [conn (nrepl/url-connect (ws-url))]
    (let [client (nrepl/client conn 5000)]
      (testing "eval returns value and namespace"
        (is (= {:value "3" :ns "user"}
               (send-message client {:op "eval" :code "(+ 1 2)"}))))

      (testing "stdout is pushed back"
        (let [messages (nrepl/message client {:op "eval" :code "(println \"hello\")"})]
          (is (some #(= "hello\n" (:out %)) messages))))

      (testing "state is preserved within a session"
        (send-message client {:op "eval" :code "(def ws-x 42)"})
        (is (= "42" (:value (send-message client {:op "eval" :code "ws-x"})))))

      (testing "describe works"
        (is (some? (:ops (send-message client {:op "describe"}))))))))

(deftest websocket-streaming
  (testing "incremental output is pushed as separate messages"
    (with-open [conn (nrepl/url-connect (ws-url))]
      (let [client (nrepl/client conn 5000)
            messages (nrepl/message
                      client
                      {:op "eval"
                       :code "(dotimes [i 3] (println i) (Thread/sleep 20))"})
            outs (keep :out messages)]
        ;; Chunk boundaries aren't guaranteed (nREPL may coalesce
        ;; output), but content and order are.
        (is (= "0\n1\n2\n" (apply str outs)))))))

(deftest websocket-client-isolation
  (testing "concurrent connections receive their own responses"
    (with-open [conn-a (nrepl/url-connect (ws-url))
                conn-b (nrepl/url-connect (ws-url))]
      (let [client-a (nrepl/client conn-a 5000)
            client-b (nrepl/client conn-b 5000)]
        (is (= "3" (:value (send-message client-a {:op "eval" :code "(+ 1 2)"}))))
        (is (= "30" (:value (send-message client-b {:op "eval" :code "(+ 10 20)"}))))))))

(defn- http-status
  [url]
  (let [conn (.openConnection (java.net.URL. url))]
    (.getResponseCode ^java.net.HttpURLConnection conn)))

(deftest plain-http-gets-upgrade-required
  (testing "a non-upgrade request receives 426 by default"
    (is (= 426 (http-status (str "http://localhost:" *port* "/"))))))

(deftest websocket-with-token-auth
  (let [handler (auth/wrap-token (websocket/ring-handler) "s3cret")
        server (jetty/run-jetty handler {:port 0 :join? false})
        port (.getLocalPort (first (.getConnectors server)))
        url (str "ws://localhost:" port "/")]
    (try
      (testing "upgrade with the right token connects and evals"
        (with-open [conn (drawbridge.websocket-client/websocket-client-transport
                          url {:http-headers {"Authorization" "Bearer s3cret"}})]
          (let [client (nrepl/client conn 5000)]
            (is (= "3" (:value (send-message client {:op "eval" :code "(+ 1 2)"})))))))

      (testing "upgrade without the token is rejected during handshake"
        (is (thrown? Exception
                     (drawbridge.websocket-client/websocket-client-transport url))))
      (finally
        (.stop server)))))

(deftest bridge-over-websocket
  (testing "socket client -> bridge -> ws endpoint"
    (let [b (bridge/start-bridge {:url (ws-url)})]
      (try
        (with-open [conn (nrepl/connect :port (:port b))]
          (let [client (nrepl/client conn 5000)]
            (is (= "42" (:value (send-message client {:op "eval" :code "(* 6 7)"}))))))
        (finally (bridge/stop-bridge b))))))
