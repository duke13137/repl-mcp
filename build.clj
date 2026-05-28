(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]))

(def lib 'is.simm/repl-mcp)
(def version "0.1.0-SNAPSHOT")
(def main 'is.simm.repl-mcp)
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))
(def src-dirs ["src"])
(def resource-dirs ["resources"])

(defn clean "Remove build artifacts." [_]
  (b/delete {:path "target"}))

(defn test "Run all the tests." [opts]
  (let [basis    (b/create-basis {:aliases [:test]})
        cmds     (b/java-command
                  {:basis     basis
                   :main      'clojure.main
                   :main-args ["-m" "cognitect.test-runner"]})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit) (throw (ex-info "Tests failed" {}))))
  opts)

(defn- uber-opts [opts]
  (assoc opts
         :lib lib :main main
         :uber-file uber-file
         :basis @basis
         :class-dir class-dir
         :src-dirs src-dirs
         :ns-compile [main]))

(defn jar "Build the library jar." [opts]
  (clean opts)
  (println "\nWriting pom.xml...")
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs src-dirs
                :resource-dirs resource-dirs})
  (println "\nCopying source and resources...")
  (b/copy-dir {:src-dirs (into resource-dirs src-dirs)
               :target-dir class-dir})
  (println "\nBuilding JAR...")
  (b/jar {:class-dir class-dir
          :jar-file jar-file})
  opts)

(defn install "Install the library jar to the local Maven repository." [opts]
  (jar opts)
  (println "\nInstalling JAR...")
  (b/install {:basis @basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir})
  opts)

(defn ci "Run the CI pipeline of tests (and build the uberjar)." [opts]
  (test opts)
  (clean opts)
  (let [opts (uber-opts opts)]
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs (into resource-dirs src-dirs) :target-dir class-dir})
    (println (str "\nCompiling " main "..."))
    (b/compile-clj opts)
    (println "\nBuilding JAR...")
    (b/uber opts))
  opts)
