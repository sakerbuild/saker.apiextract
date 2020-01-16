# saker.apiextract

![Build status](https://img.shields.io/azure-devops/build/sakerbuild/56ddae8e-b228-4ce7-a0d6-ec211126205d/17/master) [![Latest version](https://mirror.nest.saker.build/badges/saker.apiextract/version.svg)](https://nest.saker.build/package/saker.apiextract "saker.apiextract | saker.nest")

Java annotation processor for extracting API bytecode from source code. The project implements an annotation processor that examines the compiled classes and generate stub class files that contain only the declared public API fields, methods, and classes.

The processor can be used to extract a distribution that can be used to compile other applications against. It is also useful to track the changes between different releases of a single project. The processor can be also configurad to issue a warning if there's a public source element that doesn't have an associated JavaDoc.

The documentation for the project is work in progress.

## Build instructions

The project uses the [saker.build system](https://saker.build) for building. Use the following command to build the project:

```
java -jar path/to/saker.build.jar -bd build compile saker.build
```

## License

Different parts of the source code for the project is licensed under different terms. The API is licensed under *Apache License 2.0* ( [`Apache-2.0`](https://spdx.org/licenses/Apache-2.0.html)), while the annotation processor related codes are licensed under *GNU General Public License v3.0 only* ([`GPL-3.0-only`](https://spdx.org/licenses/GPL-3.0-only.html)). See the LICENSE files under the `api` and `processor` directories.

This is in order to allow more convenient usage of the library. 

Official releases of the project (and parts of it) may be licensed under different terms. See the particular releases for more information.
