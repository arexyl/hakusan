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

      # The version catalog owns the SDK versions, so the shells follow it.
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

      sdkWith =
        images:
        pkgs.androidenv.composeAndroidPackages {
          platformVersions = lib.unique (
            [ "${versions.sdk-compile}.0" ] ++ map (device: device.platform) images
          );
          buildToolsVersions = [ versions.build-tools ];
          includeCmake = false;
          includeEmulator = images != [ ];
          includeSystemImages = images != [ ];
          systemImageTypes = lib.unique (map (device: device.image) images);
          abiVersions = [ abi ];
        };

      jdk = pkgs.temurin-bin-25;

      shellWith =
        { images, tools }:
        let
          sdk = (sdkWith images).androidsdk;
        in
        pkgs.mkShellNoCC {
          # actionlint checks the shell steps of a workflow only when it finds shellcheck.
          packages = [
            jdk
            sdk
            pkgs.actionlint
            pkgs.shellcheck
          ]
          ++ tools sdk;

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
    in
    {
      devShells.${system} = {
        default = shellWith {
          images = [ ];
          tools = _: [ ];
        };

        device = shellWith {
          images = devices;
          tools = sdk: [ (createDevices sdk) ];
        };
      };

      formatter.${system} = pkgs.nixfmt-tree;
    };
}
