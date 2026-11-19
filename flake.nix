{
  description = "Development shell for Haku, the Android application of Hakusan";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      system = "aarch64-darwin";

      # The Android SDK is unfree, and its packages evaluate only with the license accepted.
      pkgs = import nixpkgs {
        inherit system;

        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      # The version catalog owns the SDK versions, so the shell follows it.
      inherit (fromTOML (builtins.readFile ./gradle/libs.versions.toml)) versions;

      android = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "${versions.sdk-compile}.0" ];
        buildToolsVersions = [ versions.build-tools ];
        includeCmake = false;
      };

      jdk = pkgs.temurin-bin-25;
    in
    {
      devShells.${system}.default = pkgs.mkShellNoCC {
        packages = [ jdk ];

        JAVA_HOME = jdk.home;
        ANDROID_HOME = "${android.androidsdk}/libexec/android-sdk";
      };

      formatter.${system} = pkgs.nixfmt-tree;
    };
}
