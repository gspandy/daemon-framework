# Version 2.6
* Spring 4.2.5
* Slf4j 1.7.21
* add condition annotations for Spring beans
    * `@BeanAvailable`
    * `@OnSystemProperty`

# Version 2.5
* Spring 4.2.3
* Slf4j 1.7.13

# Version 2.4
* use Spring 4.2.1.RELEASE

# Version 2.3
* Latest Spring version 4.2.0

# Version 2.2
* SLF4J 1.7.12
* HTTPUtils 1.9
* fix javadoc errors

# Version 2.1
* add property to set DNS TTL for Java VM
* add jvmOption to prefer IPv4 in scripts

# Version 2.0
* Adding different startmodes #15
* splitting the project in multiple modules
    * core - The core framework
    * log4j - Log4j appender configuration
    * log-simple - Simple logging configuration
    * spring - Spring starter
* possibility to set basic properties programmatically
* use SLF4J in core project and binding in submodule #14
* adding logentries.com appender
* latest dependencies