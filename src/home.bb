#!/usr/bin/env bb

;; home.bb - Simple, one file, zero dependency dotfiles manager powered by https://babashka.org/
;;
;; Repository: https://github.com/atomicptr/home.bb
;;
;; home.bb is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; home.bb is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with home.bb. If not, see <https://www.gnu.org/licenses/>.

;============ user config
(def config-file-name "homebb.edn")

;============ globals
(def version "0.3.1")
(def repository "https://github.com/atomicptr/home.bb")

(require '[babashka.cli :as cli]
         '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as string]
         '[clojure.edn :as edn])

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

(defn replace-vars [vars s]
  (reduce (fn [s [k v]] (string/replace s (re-pattern (str k)) v)) s vars))

(defn run-hook [hook vars verbose? dry-run?]
  (case (first hook)
    :shell
    (let [cmd (:cmd (second hook))
          args (vec (map (partial replace-vars vars) (or (:args (second hook)) [])))]
      (when verbose?
        (println (format "run-hook: :shell %s %s" cmd args)))
      (assert (and cmd (fs/which cmd)))

      (when-not dry-run?
        (apply shell cmd args)))

    :delete
    (let [path (second hook)
          _    (assert path)
          path (replace-vars vars path)]
      (when verbose?
        (println (format "run-hook: :delete %s" path)))

      (when-not dry-run?
        (fs/delete-if-exists path)))

    :touch
    (let [path (second hook)
          _    (assert path)
          path (replace-vars vars path)]
      (when verbose?
        (println (format "run-hook: :touch %s" path)))

      (when-not dry-run?
        (when-not (fs/exists? path)
          (fs/create-file path))))))

(defmulti install-config
  (fn [install-method opts]
    (when (:verbose? opts)
      (println (format "installing %s from '%s' to '%s'..." (:name opts) (:config-dir opts) (:target-dir opts))))
    install-method))

(defn find-leaf-files [path]
  (->> (fs/glob path "**" {:hidden true})
       (filter #(not (fs/directory? %)))
       (map str)
       (distinct)))

(defn run-for-file [fun opts files]
  (doseq [file files]
    (let [source-file-rel (str (fs/relativize (:config-dir opts) file))
          target-file     (str (fs/path (:target-dir opts) source-file-rel))]
      (fun file target-file))))

(defn is-symlink-pointing-to? [symlink file]
  (and (fs/exists? symlink)
       (fs/sym-link? symlink)
       (= file (str (fs/read-link symlink)))))

(defn link-file [file target-file opts]
  (if (is-symlink-pointing-to? target-file file)
    (when (:verbose? opts)
      (println "Target File:" target-file "is already correctly setup"))
    (do (when (:verbose? opts)
          (println "Linking File:" file "->" target-file))
        (when-not (:dry-run? opts)
          (fs/create-dirs (fs/parent target-file))
          (when (fs/exists? target-file)
            (if (:force? opts)
              (fs/delete target-file)
              (fatal (format "File '%s' already exists and would be overwritten, please back it up first or delete it." target-file))))
          (fs/create-sym-link target-file file)))))

(defmethod install-config :link/files [_ opts]
  (run-for-file
   (fn [file target-file]
     (link-file file target-file opts))
   opts
   (find-leaf-files (:config-dir opts))))

(defmethod install-config :link/dirs [_ opts]
  ; install files for root dir
  (let [files (->> (fs/glob (:config-dir opts) "*" {:hidden true})
                   (filter #(not (fs/directory? %)))
                   (map str))]
    (run-for-file
     (fn [file target-file]
       (link-file file target-file opts))
     opts
     files))
  (let [dirs (->> (fs/glob (:config-dir opts) "**" {:hidden true})
                  (filter fs/directory?)
                  (filter #(empty? (filter fs/directory? (fs/glob % "**" {:hidden true}))))
                  (map str))]
    (doseq [dir dirs]
      (assert (fs/directory? dir))
      (let [source-dir-rel (str (fs/relativize (:config-dir opts) dir))
            target-dir     (str (fs/path (:target-dir opts) source-dir-rel))]
        (if (is-symlink-pointing-to? target-dir dir)
          (when (:verbose? opts)
            (println "Target Dir:" target-dir "is already correctly setup"))
          (do (when (:verbose? opts)
                (println "Linking Dir:" dir "->" target-dir))
              (when-not (:dry-run? opts)
                (assert (not= (:target-dir opts) target-dir))
                (when (fs/directory? target-dir)
                  (fs/delete-tree target-dir))
                (fs/create-dirs (fs/parent target-dir))
                (fs/create-sym-link target-dir dir))))))))

(defmethod install-config :copy [_ opts]
  (run-for-file
   (fn [file target-file]
     (when (:verbose? opts)
       (println "Copying:" file "->" target-file))
     (when-not (:dry-run? opts)
       (fs/delete-if-exists target-file)
       (fs/create-dirs (fs/parent target-file))
       (fs/copy file target-file)))
   opts
   (find-leaf-files (:config-dir opts))))

(defn dead-symlink? [path]
  (and (fs/exists? path)
       (fs/sym-link? path)
       (not (fs/exists? (fs/read-link path)))))

(defn execute-file-processor [processor opts vars]
  (doseq [file (fs/glob (:config-dir opts) (str "**/*." (:extension processor)) {:hidden true})]
    (let [file                  (str file)
          source-file-rel       (str (fs/relativize (:config-dir opts) file))
          target-processor-file (str (fs/path (:target-dir opts) source-file-rel))
          _                     (assert (fs/exists? target-processor-file))
          target-file           (fs/strip-ext target-processor-file (:extension processor))]
      (if (fs/exists? target-file)
        (when (:verbose? opts)
          (println (format "File processor '%s': Target file '%s' already exists" (:extension processor) target-file)))
        (do (when (:verbose? opts)
              (println (format "Executing file processor '%s' on file '%s' -> '%s'" (:extension processor) target-processor-file target-file)))
            (run-hook (:run processor)
                      (merge vars {:file target-processor-file
                                   :target-file target-file})
                      (:verbose? opts)
                      (:dry-run? opts)))))))

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
        target-dir  (:target-dir config)
        config-root (if (fs/absolute? config-root)
                      config-root
                      (str (fs/path root-dir config-root)))
        _           (assert (fs/directory? config-root))
        hostname    (or (System/getenv "HOSTNAME")
                        (.. java.net.InetAddress getLocalHost getHostName))
        vars        {:hostname    hostname
                     :config-root config-root
                     :target-dir  target-dir}
        dry-run?    (:dry-run m)
        verbose?    (:verbose m)
        force?      (:force m)]
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

    ; run pre install hooks
    (when verbose?
      (println "Running pre install hooks..."))
    (doseq [hook (:pre-install config)]
      (run-hook hook vars verbose? dry-run?))

    (let [host-specific (let [host-dir (fs/path config-root (str "+" hostname))]
                          (if (fs/directory? host-dir)
                            (->> (fs/list-dir host-dir)
                                 (filter fs/directory?)
                                 (map str)
                                 (sort))
                            []))
          config-dirs   (->> (fs/list-dir config-root)
                             (filter fs/directory?)
                             (filter #(not (string/starts-with? (fs/file-name %) "+")))
                             (map str)
                             (sort))
          modules       (->> {}
                             (apply-module-paths fs/file-name config-dirs)
                             (apply-module-paths fs/file-name host-specific)
                             (into {} (map (fn [[k v]] [k (merge module-config-template v (get-in config [:overwrites k]))]))))]
      (when verbose?
        (println (format "Found configurations for %s modules..." (count modules))))

      (doseq [[k module-config] modules]
        (let [install-method (or (:install-method module-config)
                                 (:install-method config))]
          ; run config pre install hooks
          (when (and verbose? (not-empty (:pre-install module-config)))
            (println (format "Running %s pre install hooks..." k)))

          (doseq [hook (:pre-install module-config)]
            (run-hook hook vars verbose? dry-run?))

          ; install config
          (doseq [config-dir (:config-dirs module-config)]
            ; look for dead symlinks
            (doseq [link (->> (fs/glob config-dir "**" {:hidden true})
                              (filter dead-symlink?))]
              (when verbose?
                (println "Removing dead symlink" link))
              (fs/delete-if-exists link))

            (install-config install-method
                            {:name k
                             :config-root config-root
                             :config-dir config-dir
                             :target-dir target-dir
                             :module-config module-config
                             :verbose? verbose?
                             :dry-run? dry-run?
                             :force? force?})

            (doseq [processor (concat (:file-processors config)
                                      (:file-processors module-config))]
              (execute-file-processor processor
                                      {:config-dir config-dir
                                       :target-dir target-dir
                                       :verbose? verbose?
                                       :dry-run? dry-run?}
                                      vars)))

          ; run config post install hooks
          (when (and verbose? (not-empty (:post-install module-config)))
            (println (format "Running %s post install hooks..." k)))

          (doseq [hook (:post-install module-config)]
            (run-hook hook vars verbose? dry-run?)))))

    ; run post install hooks
    (when verbose?
      (println "Running post install hooks..."))

    (doseq [hook (:post-install config)]
      (run-hook hook vars verbose? dry-run?)))
  (println "\n🚀 Dotfiles successfully installed!\n"))

(def cli-spec
  {:spec
   {:config-file {:desc     (format "Specify which `%s` to use" config-file-name)
                  :alias    :C
                  :validate fs/exists?}
    :dry-run     {:desc   "Does not actually do anything except for printing what would be done"
                  :coerce :bool}
    :verbose     {:desc   "Show more information"
                  :coerce :bool}
    :force       {:desc   "Overwrites files that already exist when executing, only use this option if you know what you're doing"
                  :alias  :f
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

  (let [opts (cli/parse-opts args cli-spec)]
    (try
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

      (install opts)
      (catch Throwable t
        (error (ex-message t))
        (when (:verbose opts)
          (error t))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
