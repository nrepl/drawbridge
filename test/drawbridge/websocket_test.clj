(ns drawbridge.websocket-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [drawbridge.auth :as auth]
            [drawbridge.bridge :as bridge]
            [drawbridge.client]
            [drawbridge.websocket :as websocket]
            [drawbridge.websocket-client]
            [nrepl.core :as nrepl]
            [nrepl.transport :as transport]
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

(defn- counting-ws-handler
  "Wrap a websocket ring-handler so `open-count` tracks currently-open
  server-side WebSocket connections."
  [base open-count]
  (fn [req]
    (let [resp (base req)]
      (if-let [listener (:ring.websocket/listener resp)]
        (assoc resp :ring.websocket/listener
               (-> listener
                   (update :on-open (fn [f]
                                      (fn [socket]
                                        (swap! open-count inc)
                                        (f socket))))
                   (update :on-close (fn [f]
                                       (fn [socket code reason]
                                         (swap! open-count dec)
                                         (f socket code reason))))))
        resp))))

(defn- await-value
  "Poll until (pred) is true or `ms` elapse."
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (while (and (not (pred)) (< (System/currentTimeMillis) deadline))
      (Thread/sleep 50))
    (pred)))

(deftest bridge-closes-remote-connection
  (testing "local disconnect closes the remote WebSocket (no leak)"
    (let [open-count (atom 0)
          handler (counting-ws-handler (websocket/ring-handler) open-count)
          server (jetty/run-jetty handler {:port 0 :join? false})
          port (.getLocalPort (first (.getConnectors server)))
          b (bridge/start-bridge {:url (str "ws://localhost:" port "/")})]
      (try
        (with-open [conn (nrepl/connect :port (:port b))]
          (let [client (nrepl/client conn 5000)]
            (is (= "3" (:value (send-message client {:op "eval" :code "(+ 1 2)"}))))
            (is (= 1 @open-count))))
        ;; with-open closed the local socket; the relay must release
        ;; the remote WebSocket in response.
        (is (await-value #(zero? @open-count) 5000)
            "remote WebSocket should close after local disconnect")
        (finally
          (bridge/stop-bridge b)
          (.stop server))))))

(deftest bridge-detects-remote-death
  (testing "when the remote ws endpoint dies, the local socket is closed"
    (let [server (jetty/run-jetty (websocket/ring-handler) {:port 0 :join? false})
          port (.getLocalPort (first (.getConnectors server)))
          b (bridge/start-bridge {:url (str "ws://localhost:" port "/")})
          conn (nrepl/connect :port (:port b))]
      (try
        (let [client (nrepl/client conn 5000)]
          (is (= "3" (:value (send-message client {:op "eval" :code "(+ 1 2)"})))))
        (.stop server)
        ;; The relay should notice the dead remote and close our
        ;; socket; a read on it then throws instead of hanging forever.
        (is (thrown? Exception
                     (let [deadline (+ (System/currentTimeMillis) 10000)]
                       (loop []
                         (transport/recv conn 100)
                         (when (< (System/currentTimeMillis) deadline)
                           (recur)))))
            "local socket should be closed once the remote dies")
        (finally
          (try (.close conn) (catch Exception _))
          (bridge/stop-bridge b))))))

(deftest ws-client-uses-config-headers
  (testing "wss/ws transport falls back to the .nrepl.edn header config"
    (let [handler (auth/wrap-token (websocket/ring-handler) "cfg-secret")
          server (jetty/run-jetty handler {:port 0 :join? false})
          port (.getLocalPort (first (.getConnectors server)))]
      (try
        (with-redefs [drawbridge.client/default-http-headers
                      {"Authorization" "Bearer cfg-secret"}]
          (with-open [conn (drawbridge.websocket-client/websocket-client-transport
                            (str "ws://localhost:" port "/"))]
            (let [client (nrepl/client conn 5000)]
              (is (= "3" (:value (send-message client {:op "eval" :code "(+ 1 2)"})))))))
        (finally
          (.stop server))))))
