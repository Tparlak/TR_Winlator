# To learn more about how to use Nix to configure your environment
# see: https://developers.google.com/idx/guides/customize-idx-env
{ pkgs, ... }: {
  # Which nixpkgs channel to use.
  channel = "stable-24.11";

  # Use https://search.nixos.org/packages to find packages
  packages = [
    pkgs.jdk17
    pkgs.android-tools
  ];

  # Sets environment variables in the workspace
  env = {
    # Specify the NDK version used by the project.
    # Gradle will download this via the SDK manager if it is not already present.
    ANDROID_NDK_VERSION = "27.0.12077973";
  };

  idx = {
    # Search for the extensions you want on https://open-vsx.org/ and use "publisher.id"
    extensions = [
      "vscjav.vscode-java-pack"
      "vscjav.vscode-java-debug"
      "vscjav.vscode-java-dependency-viewer"
      "vscjav.vscode-gradle"
      "google.android-studio-helper"
    ];

    # Enable previews
    previews = {
      enable = true;
      previews = {
        # android = {
        #   command = ["./gradlew" "assembleDebug"];
        #   manager = "android";
        # };
      };
    };

    # Workspace lifecycle hooks
    workspace = {
      # Runs when a workspace is first created
      onCreate = {
        # Example: install JS dependencies from NPM
        # npm-install = "npm install";
      };
      # Runs when the workspace is (re)started
      onStart = {
        # Example: start a continuous build process
        # build-project = "gradle compileDebugSource";
      };
    };
  };
}
