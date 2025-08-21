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
         '[clojure.edn :as edn])

(def version "0.1.0-dev")
(def repository "https://github.com/atomicptr/home.bb")
(def config-file-name "homebb.edn")

(def default-config
  {:config-dir     "configs"
   :target-dir     :env/HOME
   :install-method :link/files
   :overwrites     {}})

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

(defn install [m]
  (let [config-file (or (get m :config-file)
                        (find-config-file (fs/cwd)))
        _           (assert (or config-file
                                (fs/exists? config-file)))
        config-file (str (fs/canonicalize config-file))
        root-dir    (str (fs/parent config-file))
        config      (merge default-config
                           (edn/read-string (slurp config-file)))
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
      (println "   Hostname:" hostname)
      (println "   Dry Run?:" (some? dry-run?))
      (println "  Arguments:" m)
      (println))

    (println root-dir config))

  ; TODO: parse config data properly (env/HOME, etc)
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
