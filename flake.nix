{
  description = "Development shells for Haku, the Android application of Hakusan";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      inherit (nixpkgs) lib;

      system = "aarch64-darwin";

      # The Android SDK is unfree, and its packages evaluate only with the license accepted.
      pkgs = import nixpkgs {
        inherit system;

        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      # The version catalog owns the SDK and JDK versions, so the shells follow it.
      inherit (fromTOML (builtins.readFile ./gradle/libs.versions.toml)) versions;

      abi = "arm64-v8a";

      # Each device takes the smallest image of its API level. No AOSP image has 16 KiB pages.
      devices = [
        {
          name = "hakusan_api33";
          platform = "33";
          image = "aosp_atd";
        }
        {
          name = "hakusan_api37";
          platform = "37.0";
          image = "aosp_atd";
        }
        {
          name = "hakusan_api37_16k";
          platform = "37.0";
          image = "google_apis_ps16k";
        }
      ];

      composeSdk =
        arguments:
        (pkgs.androidenv.composeAndroidPackages ({ includeCmake = false; } // arguments)).androidsdk;

      # What a build needs: the compile platform and the Build Tools.
      buildSdk = composeSdk {
        platformVersions = [ "${versions.sdk-compile}.${versions.sdk-compile-minor}" ];
        buildToolsVersions = [ versions.build-tools ];
      };

      # The emulator and the device images. nixpkgs composes every listed image type for every listed
      # platform, so the compile platform stays out: its image would serve no device.
      emulatorSdk = composeSdk {
        platformVersions = lib.unique (map (device: device.platform) devices);
        buildToolsVersions = [ ];
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = lib.unique (map (device: device.image) devices);
        abiVersions = [ abi ];
      };

      # One SDK root for the device shell: the emulator composition with the platform and the Build Tools of a build.
      # Its tools stay those of the emulator composition, so that every adb is the same one.
      deviceSdk = pkgs.runCommand "androidsdk-with-devices" { } ''
        root=$out/libexec/android-sdk
        mkdir -p "$root"

        for entry in ${emulatorSdk}/libexec/android-sdk/*; do
          case ''${entry##*/} in
            platforms | build-tools) ;;
            *) ln -s "$entry" "$root/" ;;
          esac
        done

        ln -s ${buildSdk}/libexec/android-sdk/platforms "$root/platforms"
        ln -s ${buildSdk}/libexec/android-sdk/build-tools "$root/build-tools"
        ln -s ${emulatorSdk}/bin "$out/bin"
      '';

      jdk = pkgs."temurin-bin-${versions.jdk}";

      shellWith =
        { sdk, tools }:
        pkgs.mkShellNoCC {
          # actionlint checks the shell steps of a workflow only when it finds shellcheck.
          # Zig and clang-format serve the check of the bindings header.
          packages = [
            jdk
            sdk
            pkgs.actionlint
            pkgs.shellcheck
            pkgs.zig_0_17
            pkgs.llvmPackages_23.clang-tools
          ]
          ++ tools sdk;

          HAKU_SDK_MIN = versions.sdk-min;
          JAVA_HOME = jdk.home;
          ANDROID_HOME = "${sdk}/libexec/android-sdk";
        };

      # Creates each missing device in the AVD home that the environment selects.
      createDevices =
        sdk:
        pkgs.writeShellApplication {
          name = "create-devices";
          runtimeInputs = [ sdk ];

          text = lib.concatMapStrings (
            device:
            let
              package = "system-images;android-${device.platform};${device.image};${abi}";
            in
            ''
              if ! avdmanager list avd --compact | grep --quiet --line-regexp ${lib.escapeShellArg device.name}; then
                echo no | avdmanager create avd \
                  --name ${lib.escapeShellArg device.name} \
                  --package ${lib.escapeShellArg package} \
                  --device medium_phone
              fi
            ''
          ) devices;
        };

      # Boots one device headless, runs a command against it, and stops the device.
      # The environment selects the AVD home, and the command finds the device in ANDROID_SERIAL.
      withDevice =
        sdk:
        pkgs.writeShellApplication {
          name = "with-device";
          runtimeInputs = [ sdk ];

          text = ''
            if [ "$#" -lt 2 ]; then
              echo "Usage: with-device <avd> <command>..." >&2
              exit 2
            fi

            avd=$1
            shift
            port=5554
            export ANDROID_SERIAL=emulator-$port

            attached() {
              adb devices | sed 1d | grep --quiet .
            }

            # A starting server writes its notice into the first device list.
            adb start-server >/dev/null 2>&1

            # adb lists a stopped emulator for a moment.
            for _ in $(seq 30); do
              attached || break
              sleep 1
            done

            if attached; then
              echo "A device is attached. Run one device at a time." >&2
              exit 2
            fi

            log=$(mktemp)

            emulator -avd "$avd" -port "$port" -no-window -gpu swiftshader_indirect \
              -read-only -no-snapshot -no-audio -no-boot-anim -no-metrics >"$log" 2>&1 &
            pid=$!

            stop() {
              adb emu kill >/dev/null 2>&1 || kill "$pid" 2>/dev/null || true
              wait "$pid" 2>/dev/null || true
              rm -f "$log"
            }

            trap stop EXIT

            booted() {
              [ "$(adb shell getprop sys.boot_completed 2>/dev/null </dev/null)" = 1 ]
            }

            for _ in $(seq 300); do
              booted && break

              if ! kill -0 "$pid" 2>/dev/null; then
                cat "$log" >&2
                echo "The emulator stopped before the boot completed." >&2
                exit 1
              fi

              sleep 1
            done

            if ! booted; then
              cat "$log" >&2
              echo "The boot did not complete in time." >&2
              exit 1
            fi

            "$@"
          '';
        };
    in
    {
      devShells.${system} = {
        default = shellWith {
          sdk = buildSdk;
          tools = _: [ ];
        };

        device = shellWith {
          sdk = deviceSdk;
          tools = sdk: [
            (createDevices sdk)
            (withDevice sdk)
          ];
        };
      };

      formatter.${system} = pkgs.nixfmt-tree;
    };
}
