FROM eclipse-temurin:11-alpine as build
RUN mkdir /brouter-build
WORKDIR /brouter-build

COPY . .
RUN rm -f local.properties \
    && ./gradlew build fatJar

FROM eclipse-temurin:11-alpine
RUN mkdir /brouter
WORKDIR /brouter

COPY --from=build /brouter-build/brouter-server/build/libs/brouter-*-all.jar brouter.jar
COPY --from=build /brouter-build/misc/profiles2 profiles2
COPY misc/scripts/standalone/server.sh server.sh

VOLUME ["/segments"]

ENV CLASSPATH="brouter.jar" \
    SEGMENTSPATH="/segments" \
    PROFILESPATH="profiles2" \
    PRECMD="exec"

EXPOSE 17777
ENTRYPOINT ["/bin/sh", "server.sh"]
