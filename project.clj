(defproject vrcflow "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :source-paths ["src" "../spork/src" #_""]
  :dependencies [[org.clojure/clojure  "1.9.0"]
                 [spork "0.2.0.8-SNAPSHOT"]
                 [joinr/incanter "1.9.3-SNAPSHOT"]
                 [piccolotest "0.1.2-SNAPSHOT"]
                 [org.clojure/spec.alpha "0.1.143"]
                 [org.clojure/test.check "0.9.0"]
                 ]
  :jvm-opts ^:replace ["-Xmx4g" #_"-Xmx1000m" "-XX:NewSize=200m"])
