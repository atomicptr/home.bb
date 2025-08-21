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
         '[clojure.pprint :as pp]
         '[clojure.java.io :as io])

(def default-config-name "homebb.edn")

(def default-install-method :link/files)

(def default-config
  {:config-dir "configs"
   :target-dir :env/HOME
   :modules    {}})

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

(defn help-cmd [cmd arg-spec]
  (println)
  (println (format "home.bb %s [OPTION]\n" cmd))
  (println "Options:")
  (println (cli/format-opts {:spec arg-spec})))

(def cli-spec-new
  {:from-dir {:desc "Try to infer configuration from a directory in the form of {from-dir}/config-for-app-dir"}
   :dry-run {:desc "Dry run, don't actually modify anything"
             :coerce :bool}})

(defn find-config-dirs-in-path [path]
  (->> (fs/list-dir path)
       (filter fs/directory?)
       (filter #(seq (fs/list-dir %)))
       (map fs/file-name)
       (filter #(not (string/starts-with? (str %) "+")))
       (sort)))

(defn new [m]
  (when (get-in m [:opts :help])
    (help-cmd "new" cli-spec-new)
    (System/exit 0))
  (let [target-file (str (fs/path (fs/cwd) default-config-name))
        from-dir    (get-in m [:opts :from-dir])]
    (when (and from-dir
               (or
                (not  (fs/exists? from-dir))
                (not  (fs/directory? from-dir))))
      (fatal "Directory supplied to --from-dir does not exist or isn't valid"))

    (let [data
          (-> default-config
              (assoc :config-dir (fs/file-name target-file))
              (assoc :modules    (if from-dir
                                   (into {} (map #(vector (keyword %) {:type default-install-method}) (find-config-dirs-in-path from-dir)))
                                   {})))]
      (if (get-in m [:opts :dry-run])
        (pp/pprint data)
        (with-open [w (io/writer target-file)]
          (pp/pprint
           w))))))

(def cli-spec-run
  {:dry-run {:desc "Dry run, don't actually modify anything"
             :coerce :bool}})

(defn run [m]
  ; TODO: read config file
  ; TODO: figure out environment
  ; TODO: warn user about missing config entries (in dir vs. in config file)
  ; TODO: parse config data properly (env/HOME, etc)
  ; TODO: do the deed (dry run vs actual) pre-install-hook -> install -> post-install-hook
  ;   :copy       -> delete if exists (exact leaf files) -> copy
  ;   :link/files -> link the leaf files
  ;   :link/dir   -> link the leaf dirs
  ; TODO: handle .gpg files
  (println "RUN" m))

(defn help [m]
  (println "HELP" m))

(def cli-table
  [{:cmds ["new"] :fn new :spec cli-spec-new}
   {:cmds ["run"] :fn run}
   {:cmds []      :fn help}])

(defn -main [& args]
  (when (= :windows (determine-os))
    (fatal "Windows is unsupported"))
  (when (= :unknown (determine-os))
    (fatal "Unknown operating system"))

  (cli/dispatch cli-table args {}))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
