plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.spotbot.cli.MainKt")
    applicationName = "cryptoscan"
}

dependencies {
    implementation(project(":core"))
}
