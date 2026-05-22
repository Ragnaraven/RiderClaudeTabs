{
  description = "rider-claude-tabs — JetBrains plugin that names terminal tabs after Claude Code conversations";

  inputs = {
    # Track the rolling unstable channel — picks up recent JDK 17 and Gradle 8
    # security/bugfix updates. Bumping to a `nixos-XX.YY` stable branch is a
    # project decision, not a routine `nix flake update`.
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

    flake-parts = {
      url = "github:hercules-ci/flake-parts";
      inputs.nixpkgs-lib.follows = "nixpkgs";
    };

    systems.url = "github:nix-systems/default";

    devshell = {
      url = "github:numtide/devshell";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    treefmt-nix = {
      url = "github:numtide/treefmt-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = {
    flake-parts,
    systems,
    ...
  } @ inputs:
    flake-parts.lib.mkFlake {inherit inputs;} {
      systems = import systems;

      imports = [
        inputs.devshell.flakeModule
        inputs.treefmt-nix.flakeModule
      ];

      perSystem = {pkgs, ...}: let
        # Single source of truth for the JDK. IntelliJ Platform 2024.3 pins JVM
        # target 17 (see build.gradle.kts), so the dev shell, JAVA_HOME, and
        # any wrapped Gradle invocation all reference this one binding.
        jdk = pkgs.jdk17;

        # Matches gradle-wrapper.properties (8.5). Preinstalling Gradle skips
        # the wrapper's network round-trip and lets contributors run `gradle`
        # directly outside the wrapper.
        #
        # nixpkgs' wrapped gradle takes `java` (not `jdk`) — the wrapper forwards
        # `override` args to `gradle-unwrapped`, whose param is `java ? defaultJava`.
        # The wrapper script then bakes `JAVA_HOME = ${java}` into the gradle binary.
        gradle = pkgs.gradle_8.override {java = jdk;};

        # Garnix Action body: compiles main + test sources and builds the
        # distributable plugin ZIP. Needs network for Gradle 8.5 + IntelliJ
        # Platform SDK 2024.3 downloads, so it can't be a sandbox check.
        #
        # Why no `./gradlew test`: gradle-intellij-plugin 1.x downloads the
        # JetBrains Runtime (JBR) to run the test JVM. JBR is a vanilla ELF
        # binary expecting /lib64/ld-linux-x86-64.so.2 + glibc at FHS paths,
        # which NixOS-based Garnix runners lack. `buildFHSEnv` would normally
        # paper over this, but Garnix Actions disallow nested user namespaces
        # (`bwrap: Creating new namespace failed: Operation not permitted`),
        # so the FHS chroot can't enter. `compileTestKotlin` still validates
        # that test sources type-check; full test execution stays in the dev
        # shell (`test-unit` command).
        gradleCheckApp = pkgs.writeShellApplication {
          name = "gradle-check";
          runtimeInputs = [
            jdk
            pkgs.coreutils
            pkgs.findutils
            pkgs.git
            pkgs.gnugrep
            pkgs.gnused
            pkgs.which
          ];
          text = ''
            HOME=$(mktemp -d)
            export HOME
            export JAVA_HOME="${jdk.home}"
            cd "$PWD"
            ./gradlew --no-daemon compileKotlin compileTestKotlin buildPlugin
          '';
        };
      in {
        devshells.default = {
          name = "rider-claude-tabs";

          motd = ''
            {bold}{14}🧩 rider-claude-tabs devshell{reset}
            Run {bold}menu{reset} to list available commands.
          '';

          # IntelliJ / Rider caches the project SDK by absolute path. Pointing
          # the IDE at the Nix-store path means every nixpkgs bump (new hash)
          # invalidates the cached SDK and forces reconfiguration. Symlinking
          # the resolved JDK at a stable workspace path (`./nix-jdk`, gitignored)
          # gives the IDE one location that survives JDK rebuilds. `ln -sfn`
          # overwrites the existing symlink atomically on JDK changes.
          devshell.startup.link-jdk.text = ''
            ln -sfn ${jdk.home} "$PRJ_ROOT/nix-jdk"
          '';

          env = [
            # `.home` is the nixpkgs JDK convention that handles Darwin's
            # Contents/Home suffix. Threads the pinned JDK to Gradle and any
            # IDE-launched processes spawned from this shell.
            {
              name = "JAVA_HOME";
              value = "${jdk.home}";
            }
          ];

          packages = [
            jdk
            gradle

            # SlashCommandScriptTest (src/test/.../SlashCommandScriptTest.kt)
            # shells out to `node -` to execute the embedded JS blocks in the
            # slash-command markdown files. Without Node those tests skip.
            pkgs.nodejs_22

            pkgs.git
          ];

          commands = [
            # build
            {
              category = "build";
              name = "clean";
              help = "Remove build/ and Gradle caches — fresh build state.";
              command = "exec ./gradlew clean";
            }
            {
              category = "build";
              name = "compile";
              help = "Compile Kotlin main + test sources (no tests, no packaging).";
              command = "exec ./gradlew compileKotlin compileTestKotlin";
            }
            {
              category = "build";
              name = "package";
              help = "Build the distributable plugin ZIP under build/distributions/.";
              command = "exec ./gradlew buildPlugin";
            }

            # ci
            {
              category = "ci";
              name = "check";
              help = "Run everything CI runs: unit tests, plugin build, and `nix flake check` (which covers formatting).";
              command = ''
                set -euo pipefail
                ./gradlew test buildPlugin
                nix flake check
              '';
            }
            {
              category = "ci";
              name = "fmt";
              help = "Format Nix and shell files via treefmt (`nix fmt`).";
              command = "exec nix fmt";
            }
            {
              category = "ci";
              name = "verify-plugin";
              help = "Run the IntelliJ Plugin Verifier against the built plugin.";
              command = "exec ./gradlew verifyPlugin";
            }

            # dev
            {
              category = "dev";
              name = "run-ide";
              help = "Launch a sandbox IntelliJ/Rider with this plugin loaded for manual testing.";
              command = "exec ./gradlew runIde";
            }

            # nix
            {
              category = "nix";
              name = "update-flake";
              help = "Update all flake inputs to latest revisions (rewrites flake.lock).";
              command = "exec nix flake update";
            }

            # test
            {
              category = "test";
              name = "test-ui";
              help = "Run Remote Robot UI tests — requires `test-ui-sandbox` running in another terminal on port 8082.";
              command = "exec ./gradlew uiTest";
            }
            {
              category = "test";
              name = "test-ui-sandbox";
              help = "Launch the sandbox IDE for Remote Robot UI tests (listens on port 8082).";
              command = "exec ./gradlew runIdeForUiTests";
            }
            {
              category = "test";
              name = "test-unit";
              help = "Run the default test suite (unit + storage + slash-command scripts, ~60 tests, <10s).";
              command = "exec ./gradlew test";
            }
          ];
        };

        treefmt = {
          projectRootFile = "flake.nix";
          programs = {
            alejandra.enable = true;
            shfmt.enable = true;
          };
        };

        apps.gradle-check = {
          type = "app";
          program = "${gradleCheckApp}/bin/gradle-check";
        };
      };
    };
}
