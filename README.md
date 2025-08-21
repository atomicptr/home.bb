# home.bb

Simple, one file, zero dependency dotfiles manager powered by [Babashka](https://babashka.org/)

## Installation

The recommended way to install home.bb is to copy the file into your dotfiles repository and execute it from there whenever needed

**Note**: You will need to have [Babashka](https://babashka.org/) installed.

```bash
# First go to your dotfiles repo
$ cd ./dotfiles

# Next lets download home.bb
$ wget https://raw.githubusercontent.com/atomicptr/home.bb/refs/heads/master/src/home.bb

# And lastly execute it using bb
$ bb ./home.bb

# Alternatively you can also add execute permissions and execute it right away:
$ chmod +x ./home.bb
$ ./home.bb
```

home.bb does have some options which you can check out using the `--help`

## Usage

home.bb is mostly using "convention over configuration", so it assumes you have a config directory containing your dotfiles grouped by application like this:

```
configs/
    hyprland/
        .config/hypr/
            hyprland.conf
    quickshell/
        .config/quickshell/
            shell.qml
    ...
```

and for this setup you only need a configuration file called `homebb.edn` in your root directory like this:

```clojure
{:config-dir "configs"
 :target-dir :env/HOME}
```

this will install e.g. `configs/hyprland/.config/hypr/hyprland.conf` to be sym linked to `$HOME/.config/hypr/hyprland.conf`, essentially the first directory
below the group (in this case `hyprland` is emulating where the file will be in your target directory).

With this you have a working dotfiles manager, congratulations!

### Host specific configurations

In addition to setting up application configs, you can also create configurations only for systems with a specific hostname (prefixed with a `+`)

Lets assume our hostname is `gaming-pc` and we wanna add some special configurations to make the system faster

```
configs/
    +gaming-pc/
        hyprland/.config/hypr/
            performance.conf
    +work-notebook/
        hyprland/.config/hypr/
            work-stuff.conf
    hyprland/
        .config/hypr/
            hyprland.conf
    quickshell/
        .config/quickshell/
            shell.qml
    ...
```

When running home.bb it will now install the `configs/hyprland/.config/hypr/performance.conf` into `$HOME/.config/hypr/`

### Install Methods

By default all files are installed by linking the leaf-files (`:link/files`) to the target directory, but there are two other ways of installing your dotfiles:

- **Copy** (`:copy`): Which will copy the file over instead of symlinking it, useful for applications that don't support symlinks
- **Link Directory** (`:link/dirs`): This will create symlinks for every leaf directory if we'd use this with our earlier hyprland example, `configs/hyprland/.config/hypr` would be symlinked to `$HOME/.config/hypr`

#### Configuring install methods

You can either change this globally by setting the `:install-method` option

```clojure
{:config-dir "configs"
 :target-dir :env/HOME

 :install-method :link/files}
```

or by applying an overwrite for a specific group, in this case again we'll write one for hyprland

```clojure
{:config-dir "configs"
 :target-dir :env/HOME

 :overwrites
 {:hyprland {:install-method :link/dirs}}
```

### Pre/Post Install Hooks

In addition to installing dotfiles we can also execute commands before/after executing the installation process

As with the install methods you have the option of either doing this globally (which will only execute it once before/after installing the groups)

```clojure
{:config-dir "configs"
 :target-dir :env/HOME

 :pre-install  [[:shell {:cmd "fastfetch"}] ; lets print our system info before
                [:shell {:cmd "date" :args ["+%s"]}] ; and also the linux timestamp for whatever reason
 :post-install [[:shell {:cmd "date" :args ["+%s"]}]]} ; and again after we installed everything, for benchmarking maybe?
```

or on a per application basis


```clojure
{:config-dir "configs"
 :target-dir :env/HOME

 :overwrites
 {:quickshell {:pre-install  [[:delete ":target-dir/.config/quickshell/.qmlls.ini"]]    ; delete this file before install
               :post-install [[:touch  ":target-dir/.config/quickshell/.qmlls.ini"]]}}} ; and create it again after
```

As you might've noticed we can use some variables here, here a simple list of whats available:

```clojure
{:hostname    "your systems hostname"
 :config-root "absolute path to your config root (configs)"
 :target-dir  "absolute path to the target dir (:env/HOME)"}
```

## License

GPLv3
