#!/usr/bin/env bb

;; home.bb - A simple configuration driven dotfiles manager made using Babashka
;;
;; Repository: https://github.com/atomicptr/home.bb
;;
;; Project X is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; Project X is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with Project X. If not, see <https://www.gnu.org/licenses/>.

(require '[babashka.cli :as cli]
         '[babashka.fs :as fs]
         '[clojure.string :as string]
         '[clojure.edn :as edn]
         '[clojure.pprint :as pp])

(def version "0.1.0-dev")
(def repository "https://github.com/atomicptr/home.bb")
(def config-file-name "homebb.edn")

(def default-config
  {:config-dir     "configs"
   :target-dir     :env/HOME
   :install-method :link/files
   :pre-install    []
   :post-install   []
   :overwrites     {}})

(def module-config-template
  {:install-method nil
   :pre-install    []
   :post-install   []})

(defn determine-os []
  (let [os-name (string/lower-case (System/getProperty "os.name"))]
    (cond
      (string/includes? os-name "linux")   :linux
      (string/includes? os-name "mac")     :mac
      (string/includes? os-name "windows") :windows
      :else                                :unknown)))

(defn error [& args]
  (apply println "ERR:" args))

(defn fatal [& args]
  (apply error args)
  (System/exit 1))

(defn find-config-file [from-dir]
  (loop [curr from-dir]
    (let [target-file (fs/path curr config-file-name)]
      (cond
        (fs/exists? target-file)
        (str target-file)

        (nil? (fs/parent curr))
        nil

        :else
        (recur (fs/parent curr))))))

(defn expand-env-vars [m]
  (cond
    (map? m)
    (into {} (map (fn [[k v]] [k (expand-env-vars v)]) m))

    (vector? m)
    (mapv expand-env-vars m)

    (seq? m)
    (map expand-env-vars m)

    (and (keyword? m)
         (= "env" (namespace m)))
    (let [env (System/getenv (name m))]
      (when-not env
        (throw (ex-info (format "Configuration contains request for environment variable: '%s' which is unset" (name m)) {})))
      env)

    :else m))

(defn expand-config [config]
  (->> config
       expand-env-vars))

(defn apply-module-paths [fun paths m]
  (reduce
   (fn [acc path]
     (let [key (keyword (fun path))]
       (update
        acc
        key
        (fn [existing]
          (if existing
            (update existing :config-dirs conj path)
            {:config-dirs [path]})))))
   m
   paths))

(defn install [m]
  (let [config-file (or (get m :config-file)
                        (find-config-file (fs/cwd)))
        _           (assert (or config-file
                                (fs/exists? config-file)))
        config-file (str (fs/canonicalize config-file))
        root-dir    (str (fs/parent config-file))
        config      (merge default-config
                           (edn/read-string (slurp config-file)))
        config      (expand-config config)
        config-root (:config-dir config)
        config-root (if (fs/absolute? config-root)
                      config-root
                      (str (fs/path root-dir config-root)))
        _           (assert (fs/directory? config-root))
        hostname    (or (System/getenv "HOSTNAME")
                        (.. java.net.InetAddress getLocalHost getHostName))
        dry-run?    (:dry-run m)
        verbose?    (:verbose m)]
    (when verbose?
      (println)
      (println "============ home.bb")
      (println "    Version:" version)
      (println "Config File:" config-file)
      (println "   Root Dir:" root-dir)
      (println "Config Root:" config-root)
      (println "   Hostname:" hostname)
      (println "   Dry Run?:" (some? dry-run?))
      (println "  Arguments:" m)
      (println))

    (let [host-specific (let [host-dir (fs/path config-root (str "+" hostname))]
                          (if (fs/directory? host-dir)
                            (->> (fs/list-dir host-dir)
                                 (filter fs/directory?)
                                 (map str)
                                 (sort))
                            []))
          config-dirs       (->> (fs/list-dir config-root)
                                 (filter fs/directory?)
                                 (filter #(not (string/starts-with? (fs/file-name %) "+")))
                                 (map str)
                                 (sort))
          modules           (->> {}
                                 (apply-module-paths fs/file-name config-dirs)
                                 (apply-module-paths fs/file-name host-specific)
                                 (into {} (map (fn [[k v]] [k (merge module-config-template v (get-in config [:overwrites k]))]))))]
      (when verbose?
        (println (format "Found %s configurations..." (count modules))))
      (pp/pprint modules))

    (println root-dir config))

  ; TODO: do the deed (dry run vs actual) pre-install-hook -> install -> post-install-hook
  ;   :copy       -> delete if exists (exact leaf files) -> copy
  ;   :link/files -> link the leaf files
  ;   :link/dir   -> link the leaf dirs
  ; TODO: handle .gpg files
  (println "RUN" (find-config-file (fs/cwd))))

(def cli-spec
  {:spec
   {:config-file {:desc     (format "Specify which `%s` to use" config-file-name)
                  :alias    :C
                  :validate fs/exists?}
    :dry-run     {:desc   "Does not actually do anything except for printing what would be done"
                  :coerce :bool}
    :verbose     {:desc   "Show more information"
                  :coerce :bool}
    :version     {:desc   "Show home.bb version"
                  :coerce :bool}
    :help        {:desc   "Show help message"
                  :coerce :bool}}})

(defn -main [& args]
  (when (= :windows (determine-os))
    (fatal "Windows is unsupported"))
  (when (= :unknown (determine-os))
    (fatal "Unknown operating system"))

  (try
    (let [opts (cli/parse-opts args cli-spec)]
      (when (:version opts)
        (println version)
        (System/exit 0))

      (when (:help opts)
        (println
         (format "\nhome.bb - A simple configuration driven dotfiles manager made using Babashka\n\n  Repository:\t%s\n  Version:\t%s\n"
                 repository
                 version))
        (println "Options:\n")
        (println (cli/format-opts cli-spec))
        (System/exit 0))

      (install opts))
    (catch Throwable t
      (error (ex-message t)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
