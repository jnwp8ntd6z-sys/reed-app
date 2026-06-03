// Сборка Android AAR ядра VLESS (sing-box) из локального модуля singbox-mobile/ через
// gomobile. Зеркало olcrtc-bin/build.gradle.kts. Go-модуль лежит в КОРНЕ репо
// (../../singbox-mobile относительно этого модуля), go.sum не закоммичен — поэтому
// перед bind прогоняем `go mod tidy` (как в изолированном workflow singbox-core.yml,
// где Android+iOS bind уже зелёные).
val singboxRepoPath = providers.environmentVariable("SINGBOX_REPO")
    .orElse(rootProject.layout.projectDirectory.asFile.parentFile.resolve("singbox-mobile").absolutePath)
val singboxRepoDir = rootProject.file(singboxRepoPath.get())
val singboxAndroidAarFile = layout.buildDirectory.file("singbox.aar").get().asFile

val gomobileExecutable = providers.environmentVariable("GOMOBILE_PATH")
    .orElse(
        providers.systemProperty("user.home")
            .map { "$it/go/bin/gomobile" }
    ).get()

val buildSingboxAndroidAar by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds sing-box (VLESS) Android AAR from SINGBOX_REPO using gomobile."
    inputs.files(
        singboxRepoDir.resolve("mobile.go"),
        singboxRepoDir.resolve("tools.go"),
        singboxRepoDir.resolve("go.mod")
    )
    outputs.file(singboxAndroidAarFile)

    workingDir = singboxRepoDir

    val goBin = file("${System.getProperty("user.home")}/go/bin").absolutePath
    val path = System.getenv("PATH") ?: ""
    val mergedPath = if (path.contains(goBin)) path else "$goBin:$path"

    // go.sum генерируется тут же: go get x/mobile + go mod tidy, затем gomobile bind.
    commandLine(
        "sh", "-c",
        "export PATH=\"$mergedPath\"; " +
            "go get golang.org/x/mobile/bind@latest && " +
            "go mod tidy -compat=1.24 && " +
            "\"$gomobileExecutable\" bind " +
            "-target=android/arm,android/arm64,android/amd64 " +
            "-androidapi 21 -ldflags \"-s -w -checklinkname=0\" " +
            "-o \"${singboxAndroidAarFile.absolutePath}\" ."
    )
}

configurations.maybeCreate("default")
artifacts.add("default", singboxAndroidAarFile) {
    builtBy(buildSingboxAndroidAar)
}
