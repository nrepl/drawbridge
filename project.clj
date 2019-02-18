(defproject nrepl/drawbridge "0.2.1-SNAPSHOT"
  :description "HTTP transport support for Clojure's nREPL implemented as a Ring handler."
  :url "http://github.com/nrepl/drawbridge"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git" :url "https://github.com/nrepl/drawbridge"}
  :dependencies [[nrepl "0.6.0"]
                 [ring/ring-core "1.7.1"]
                 [cheshire "5.8.1"]
                 ;; client
                 [clj-http "3.9.1"]]

  :aliases {"test-all" ["with-profile" "+1.7:+1.8:+1.9:+1.10" "test"]}

  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]]

  :profiles {:dev {:dependencies [[compojure "1.6.1"]
                                  [ring/ring-jetty-adapter "1.7.1"]
                                  [ring "1.7.1"]]}
             :provided {:dependencies [[org.clojure/clojure "1.10.0"]]}
             :1.7 {:dependencies [[org.clojure/clojure "1.7.0"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :1.10 {:dependencies [[org.clojure/clojure "1.10.0"]]}

             :cljfmt {:plugins [[lein-cljfmt "0.6.1"]]}})
