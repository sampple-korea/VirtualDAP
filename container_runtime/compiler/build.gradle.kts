plugins { `java-library` }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets["main"].java.srcDir(rootProject.file("third_party/blackbox/compiler/src/main/java"))
sourceSets["main"].resources.srcDir(rootProject.file("third_party/blackbox/compiler/src/main/resources"))

dependencies {
    annotationProcessor("com.google.auto.service:auto-service:1.1.1")
    compileOnly("com.google.auto.service:auto-service:1.1.1")
    implementation("com.squareup:javapoet:1.13.0")
    implementation(project(":containerReflection"))
}
