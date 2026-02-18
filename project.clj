(defproject nrepl/drawbridge "0.2.1"
  :description "HTTP transport support for Clojure's nREPL implemented as a Ring handler."
  :url "http://github.com/nrepl/drawbridge"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git" :url "https://github.com/nrepl/drawbridge"}
  :dependencies [[nrepl "1.5.2"]
                 [ring/ring-core "1.15.3"]
                 [cheshire "6.1.0"]
                 ;; client
                 [clj-http "3.13.1"]]

  :aliases {"test-all" ["with-profile" "+1.10:+1.11:+1.12" "test"]}

  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]]

  :profiles {:dev {:dependencies [[compojure "1.7.2"]
                                  [ring/ring-jetty-adapter "1.15.3"]
                                  [ring "1.15.3"]]}
             :provided {:dependencies [[org.clojure/clojure "1.10.0"]]}
             :1.10 {:dependencies [[org.clojure/clojure "1.10.0"]]}
             :1.11 {:dependencies [[org.clojure/clojure "1.11.4"]]}
             :1.12 {:dependencies [[org.clojure/clojure "1.12.0"]]}

             :cljfmt {:plugins [[lein-cljfmt "0.9.2"]]}})
