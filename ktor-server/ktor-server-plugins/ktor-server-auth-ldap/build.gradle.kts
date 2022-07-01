kotlin.sourceSets {
    val jvmMain by getting {
        dependencies {
            api(project(":ktor-server:ktor-server-plugins:ktor-server-auth"))
        }
    }
    val jvmTest by getting {
        dependencies {
            api("org.apache.directory.server:apacheds-server-integ:2.0.0.AM26")
            api("org.apache.directory.server:apacheds-core-integ:2.0.0-M24")
        }
    }
}
