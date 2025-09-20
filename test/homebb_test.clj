(ns homebb-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [babashka.fs :as fs]))

(load-file "src/home.bb")

(def test-dir (atom nil))

(declare install dead-symlink?)

(defn setup-temp-dir []
  (reset! test-dir (str (fs/create-temp-dir {:prefix "homebb-test"})))
  (fs/create-dirs @test-dir)
  (fs/create-dirs (fs/path @test-dir "configs"))
  (fs/create-dirs (fs/path @test-dir "home")))

(defn target-dir
  ([] (str (fs/path @test-dir "home")))
  ([path] (str (fs/path @test-dir "home" path))))

(defn test-file-path [config-name file]
  (fs/path @test-dir "configs" config-name file))

(defn cleanup-temp-dir []
  (when @test-dir
    (fs/delete-tree @test-dir)
    (reset! test-dir nil)))

(use-fixtures
  :each
  (fn [f]
    (setup-temp-dir)
    (f)
    (cleanup-temp-dir)))

(defn create-config [config-map]
  (let [config-file (fs/path @test-dir "homebb.edn")]
    (spit (str config-file) (pr-str config-map))
    (str config-file)))

(defn create-test-files [config-name files]
  (doseq [[path content] files]
    (let [full-path (fs/path @test-dir "configs" config-name path)]
      (fs/create-dirs (fs/parent full-path))
      (spit (str full-path) content))))

(deftest ^:integration test-simple-install-link-files
  (testing "creating install with install method :link/files"
    (create-config {:config-dir     "configs"
                    :target-dir     (target-dir)
                    :install-method :link/files})

    (create-test-files "vim" {".vimrc" "set number\n"
                              ".vim/colors/theme.vim" "colorscheme dark\n"})
    (create-test-files "git" {".gitconfig" "# Hello World!"})

    (install {:config-file (str (fs/path @test-dir "homebb.edn"))
              :verbose true})

    (is (fs/sym-link? (target-dir ".vimrc")))
    (is (fs/sym-link? (target-dir ".vim/colors/theme.vim")))
    (is (= "set number\n" (slurp (target-dir ".vimrc"))))
    (is (= "colorscheme dark\n" (slurp (target-dir ".vim/colors/theme.vim"))))
    (is (fs/sym-link? (target-dir ".gitconfig")))
    (is (= "# Hello World!" (slurp (target-dir ".gitconfig"))))))

(deftest ^:integration test-simple-install-link-dirs
  (testing "creating install with install method :link/dirs"
    (create-config {:config-dir     "configs"
                    :target-dir     (target-dir)
                    :install-method :link/dirs})

    (create-test-files "vim" {".vimrc" "set number\n"
                              ".vim/colors/theme.vim" "colorscheme dark\n"})
    (create-test-files "git" {".gitconfig" "# Hello World!"})

    (install {:config-file (str (fs/path @test-dir "homebb.edn"))
              :verbose true})

    (is (fs/sym-link? (target-dir ".vimrc")))
    (is (not (fs/sym-link? (target-dir ".vim/colors/theme.vim"))))
    (is (fs/sym-link? (fs/parent (target-dir ".vim/colors/theme.vim"))))
    (is (not (fs/sym-link? (fs/parent (fs/parent (target-dir ".vim/colors/theme.vim"))))))
    (is (= "set number\n" (slurp (target-dir ".vimrc"))))
    (is (= "colorscheme dark\n" (slurp (target-dir ".vim/colors/theme.vim"))))
    (is (fs/sym-link? (target-dir ".gitconfig")))
    (is (= "# Hello World!" (slurp (target-dir ".gitconfig"))))))

(deftest ^:integration test-simple-install-copy
  (testing "creating install with install method :link/files"
    (create-config {:config-dir     "configs"
                    :target-dir     (target-dir)
                    :install-method :copy})

    (create-test-files "vim" {".vimrc" "set number\n"
                              ".vim/colors/theme.vim" "colorscheme dark\n"})
    (create-test-files "git" {".gitconfig" "# Hello World!"})

    (install {:config-file (str (fs/path @test-dir "homebb.edn"))
              :verbose true})

    (is (not (fs/sym-link? (target-dir ".vimrc"))))
    (is (not (fs/sym-link? (target-dir ".vim/colors/theme.vim"))))
    (is (= "set number\n" (slurp (target-dir ".vimrc"))))
    (is (= "colorscheme dark\n" (slurp (target-dir ".vim/colors/theme.vim"))))
    (is (not (fs/sym-link? (target-dir ".gitconfig"))))
    (is (= "# Hello World!" (slurp (target-dir ".gitconfig"))))))

(deftest ^:integration test-removing-dead-symlinks
  (testing "remove dead symlinks"
    (create-config {:config-dir     "configs"
                    :target-dir     (target-dir)
                    :install-method :link/files})

    (create-test-files "vim"        {".vimrc" "set number\n"
                                     ".vim/colors/theme.vim" "colorscheme dark\n"
                                     ".vim/colors/bright.vim" "colorscheme bright\n"})
    (create-test-files "git"        {".gitconfig" "# Hello World!"})
    (create-test-files "wallpapers" {".local/share/wallpapers/wallpaper" "This is an image"})

    (install {:config-file (str (fs/path @test-dir "homebb.edn"))
              :verbose true})

    (is (fs/exists? (test-file-path "vim" ".vim/colors/theme.vim")))
    (is (fs/sym-link? (target-dir ".vim/colors/theme.vim")))

    (fs/delete-if-exists (test-file-path "vim" ".vim/colors/theme.vim"))
    (is (not (fs/exists? (test-file-path "vim" ".vim/colors/theme.vim"))))

    (is (dead-symlink? (target-dir ".vim/colors/theme.vim")))

    (install {:config-file (str (fs/path @test-dir "homebb.edn"))
              :verbose true})

    (is (not (fs/sym-link? (target-dir ".vim/colors/theme.vim"))))))
