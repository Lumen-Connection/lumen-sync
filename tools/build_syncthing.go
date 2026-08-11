// Command build_syncthing fetches and builds the pinned Syncthing release.
//
// The checkout lives under .cache. Set SYNCTHING_SOURCE_DIR to use a verified
// offline checkout, such as the source tree provided by an F-Droid build.
package main

import (
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
)

func main() {
	if len(os.Args) != 2 || (os.Args[1] != "desktop" && os.Args[1] != "android") {
		fatalf("usage: go run tools/build_syncthing.go <desktop|android>")
	}
	root, err := os.Getwd()
	check(err)
	versionBytes, err := os.ReadFile(filepath.Join(root, "SYNCTHING_VERSION"))
	check(err)
	version := strings.TrimSpace(string(versionBytes))
	source := os.Getenv("SYNCTHING_SOURCE_DIR")
	if source == "" {
		source = filepath.Join(root, ".cache", "syncthing", version)
	}
	if _, err := os.Stat(filepath.Join(source, "go.mod")); err != nil {
		check(os.MkdirAll(filepath.Dir(source), 0o755))
		run(root, nil, "git", "clone", "--branch", version, "--depth", "1", "https://github.com/syncthing/syncthing.git", source)
	}
	tag := output(source, "git", "describe", "--tags", "--exact-match")
	if strings.TrimSpace(tag) != version {
		fatalf("expected Syncthing %s, found %s in %s", version, strings.TrimSpace(tag), source)
	}

	if os.Args[1] == "desktop" {
		buildDesktop(root, source)
	} else {
		buildAndroid(root, source)
	}
}

func buildDesktop(root, source string) {
	cleanBuiltBinaries(source)
	run(source, nil, "go", "run", "build.go", "-no-upgrade", "build")
	artifact := findBuiltBinary(source, runtime.GOOS == "windows")
	resourcePlatform := map[string]string{
		"windows": "windows",
		"linux":   "linux",
		"darwin":  "macos",
	}[runtime.GOOS]
	if resourcePlatform == "" {
		fatalf("unsupported desktop build host: %s", runtime.GOOS)
	}
	name := "syncthing"
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	destination := filepath.Join(root, "composeApp", "src", "desktopMain", "appResources", resourcePlatform, name)
	copyFile(artifact, destination, 0o755)
	fmt.Println("Built", destination)
}

func buildAndroid(root, source string) {
	ndk := findNDK()
	hosts := map[string]string{
		"windows": "windows-x86_64",
		"linux":   "linux-x86_64",
		"darwin":  "darwin-x86_64",
	}
	host, ok := hosts[runtime.GOOS]
	if !ok {
		fatalf("unsupported Android build host: %s", runtime.GOOS)
	}
	compiler := "aarch64-linux-android26-clang"
	if runtime.GOOS == "windows" {
		compiler += ".cmd"
	}
	compiler = filepath.Join(ndk, "toolchains", "llvm", "prebuilt", host, "bin", compiler)
	if _, err := os.Stat(compiler); err != nil {
		fatalf("Android compiler not found: %s", compiler)
	}
	// anet uses a documented net.zoneCache linkname on Android. Go 1.23+
	// requires the explicit linker opt-out until that dependency is replaced.
	environment := append(
		os.Environ(),
		"GO111MODULE=on",
		"CGO_ENABLED=1",
		"EXTRA_LDFLAGS=-checklinkname=0",
	)
	cleanBuiltBinaries(source)
	run(source, environment, "go", "run", "build.go", "-goos", "android", "-goarch", "arm64", "-cc", compiler, "-no-upgrade", "build")
	artifact := findBuiltBinary(source, false)
	destination := filepath.Join(root, "composeApp", "src", "androidMain", "jniLibs", "arm64-v8a", "libsyncthing.so")
	copyFile(artifact, destination, 0o755)
	fmt.Println("Built", destination)
}

func findNDK() string {
	if explicit := os.Getenv("ANDROID_NDK_HOME"); explicit != "" {
		return explicit
	}
	androidHome := os.Getenv("ANDROID_HOME")
	if androidHome == "" {
		androidHome = os.Getenv("ANDROID_SDK_ROOT")
	}
	if androidHome == "" {
		fatalf("set ANDROID_NDK_HOME, ANDROID_HOME, or ANDROID_SDK_ROOT")
	}
	root := filepath.Join(androidHome, "ndk")
	entries, err := os.ReadDir(root)
	check(err)
	var versions []string
	for _, entry := range entries {
		if entry.IsDir() {
			versions = append(versions, entry.Name())
		}
	}
	if len(versions) == 0 {
		fatalf("no NDK installation found below %s", root)
	}
	sort.Sort(sort.Reverse(sort.StringSlice(versions)))
	return filepath.Join(root, versions[0])
}

func cleanBuiltBinaries(source string) {
	for _, name := range []string{"syncthing", "syncthing.exe"} {
		_ = os.Remove(filepath.Join(source, name))
		_ = os.Remove(filepath.Join(source, "bin", name))
	}
}

func findBuiltBinary(source string, windowsTarget bool) string {
	names := []string{"syncthing"}
	if windowsTarget {
		names = []string{"syncthing.exe"}
	}
	for _, name := range names {
		for _, candidate := range []string{filepath.Join(source, name), filepath.Join(source, "bin", name)} {
			if _, err := os.Stat(candidate); err == nil {
				return candidate
			}
		}
	}
	fatalf("Syncthing build completed without producing an executable")
	return ""
}

func copyFile(source, destination string, mode os.FileMode) {
	check(os.MkdirAll(filepath.Dir(destination), 0o755))
	input, err := os.Open(source)
	check(err)
	defer input.Close()
	output, err := os.OpenFile(destination, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode)
	check(err)
	_, err = io.Copy(output, input)
	closeErr := output.Close()
	check(err)
	check(closeErr)
}

func run(directory string, environment []string, name string, args ...string) {
	fmt.Println("+", name, strings.Join(args, " "))
	command := exec.Command(name, args...)
	command.Dir = directory
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	command.Stdin = os.Stdin
	if environment != nil {
		command.Env = environment
	}
	check(command.Run())
}

func output(directory, name string, args ...string) string {
	command := exec.Command(name, args...)
	command.Dir = directory
	bytes, err := command.Output()
	check(err)
	return string(bytes)
}

func check(err error) {
	if err != nil {
		fatalf("%v", err)
	}
}

func fatalf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
	os.Exit(1)
}
