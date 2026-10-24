plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
}
