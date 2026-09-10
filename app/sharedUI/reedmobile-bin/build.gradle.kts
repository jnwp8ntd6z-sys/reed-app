// Единый Android AAR с ОБОИМИ нативными движками: olcRTC (пакет mobile) + sing-box/VLESS
// (пакет singboxmobile). Собирается ОДНИМ gomobile bind из go-модуля singbox-mobile/
// (он require'ит и sing-box, и olcrtc) → ОДНА libgojni.so. Это заменяет olcrtc-bin:
// два отдельных gomobile-AAR в одном APK невозможны (конфликт libgojni.so) — проверено
// в singbox-core.yml (совмещённый bind зелёный, AAR содержит mobile/ + singboxmobile/).
//
// go.sum не закоммичен и olcrtc не запинён — задача сама делает go get + go mod tidy,
// как в изолированном workflow.
val singboxRepoPath = providers.environmentVariable("SINGBOX_REPO")
    .orElse(rootProject.layout.projectDirectory.asFile.parentFile.resolve("singbox-mobile").absolutePath)
val singboxRepoDir = rootProject.file(singboxRepoPath.get())
val reedmobileAarFile = layout.buildDirectory.file("reedmobile.aar").get().asFile

val gomobileExecutable = providers.environmentVariable("GOMOBILE_PATH")
    .orElse(
        providers.systemProperty("user.home")
            .map { "$it/go/bin/gomobile" }
    ).get()

val buildReedmobileAar by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds combined olcRTC+sing-box Android AAR (one libgojni.so) via gomobile."
    inputs.files(
        singboxRepoDir.resolve("mobile.go"),
        singboxRepoDir.resolve("tools.go"),
        singboxRepoDir.resolve("go.mod")
    )
    outputs.file(reedmobileAarFile)

    workingDir = singboxRepoDir

    val goBin = file("${System.getProperty("user.home")}/go/bin").absolutePath
    val path = System.getenv("PATH") ?: ""
    val mergedPath = if (path.contains(goBin)) path else "$goBin:$path"
    val aar = reedmobileAarFile.absolutePath

    commandLine(
        "sh", "-c",
        "set -e; export PATH=\"$mergedPath\"; " +
            "go get github.com/openlibrecommunity/olcrtc@master; " +
            "go get golang.org/x/mobile/bind@6129f5bee9d5; " +
            "go mod tidy; " +
            "\"$gomobileExecutable\" bind " +
            "-target=android/arm,android/arm64,android/amd64 " +
            // -tags with_utls ОБЯЗАТЕЛЕН: REALITY-клиент sing-box без него не работает
            // (VLESS Reality сразу падает на старте → мгновенный сброс на Android).
            "-tags with_utls " +
            "-androidapi 21 -ldflags \"-s -w -checklinkname=0\" " +
            "-o \"$aar\" github.com/openlibrecommunity/olcrtc/mobile . ; " +
            // DIAG: какие версии olcrtc/pion зарезолвились и что реально в aar
            "echo '###GOLIST###'; go list -m all 2>/dev/null | grep -iE 'openlibrecommunity/olcrtc|pion/webrtc|golang.org/x/mobile' | head; " +
            "echo '###AAR###'; ( cd /tmp && rm -rf _z && mkdir _z && cd _z && unzip -o -q \"$aar\" classes.jar && unzip -l classes.jar | grep -iE 'mobile/' | head -80 ); echo '###ENDDIAG###'"
    )
}

configurations.maybeCreate("default")
artifacts.add("default", reedmobileAarFile) {
    builtBy(buildReedmobileAar)
}
