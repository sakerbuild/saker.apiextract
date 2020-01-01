# saker.apiextract

Java annotation processor for extracting API bytecode from source code. The project implements an annotation processor that examines the compiled classes and generate stub class files that contain only the declared public API fields, methods, and classes.

The processor can be used to extract a distribution that can be used to compile other applications against. It is also useful to track the changes between different releases of a single project. The processor can be also configurad to issue a warning if there's a public source element that doesn't have an associated JavaDoc.

The documentation for the project is work in progress.

## Build instructions

The project uses the [saker.build system](https://saker.build) for building. Use the following command to build the project:

```
java -jar path/to/saker.build.jar -bd build compile saker.build
```

## License

TBD TODO