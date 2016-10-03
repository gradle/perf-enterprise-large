# Large Enterprise Performance Reproduction Project

## Getting Started

Clone the repo:
```sh
$> git clone https://github.com/gradle/perf-enterprise-large.git
$> cd perf-enterprise-large
```

From here you need to run the generation script. It takes about 30 seconds to do it's work.
```sh
$> ./setup.sh
```

Copy `gradle.properties.sample` as `gradle.properties`
```sh
$> cp gradle.properties.sample gradle.properties
```

At this point, you should be able to run the build.
```sh
$> gradle -g gradle-user-home resolveDependencies
```

## Testing downloading

Remove the `gradle-user-home` directory and create a new one to test downloading from a clean state.
```sh
$> rm -rf gradle-user-home
$> mkdir gradle-user-home
```

Make sure to pass `-g gradle-user-home` to the gradle command.
```sh
$> gradle -g gradle-user-home resolveDependencies
```

Enabling access logging for maven-server
```sh
$> export MAVEN_SERVER_ACCESS_LOG=$PWD/maven-server.log
```

## Integrated profiling tools

Use profiling scripts from [perf-native-large](https://github.com/gradle/perf-native-large).

Add a symbolic link to the profiler directory
```
ln -s ../perf-native-large/profiler
```

[Follow instructions](https://github.com/gradle/perf-native-large#integrated-profiling-tools)