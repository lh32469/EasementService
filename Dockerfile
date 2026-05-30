FROM eclipse-temurin:25-jre-jammy

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      tesseract-ocr \
      tesseract-ocr-eng \
    && rm -rf /var/lib/apt/lists/*

RUN rm /etc/localtime
RUN ln -s /usr/share/zoneinfo/PST8PDT /etc/localtime

COPY target/*.jar /usr/src/app.jar
WORKDIR                 /usr/src/

ENV _JAVA_OPTIONS="-XX:+UseShenandoahGC \
-XX:+UseCompactObjectHeaders \
-Djdk.xml.maxGeneralEntitySizeLimit=0 \
-Djdk.xml.entityExpansionLimit=0 \
-Djdk.xml.totalEntitySizeLimit=0 \
-Djdk.xml.maxGeneralEntitySizeLimit=0 \
-Xmx2g \
-XX:ActiveProcessorCount=2 \
-XX:+UnlockExperimentalVMOptions \
-XX:MetaspaceSize=25m \
-XX:MinMetaspaceFreeRatio=10 \
-XX:ShenandoahUncommitDelay=1000 \
-XX:ShenandoahGuaranteedGCInterval=10000"

CMD ["java", "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-jar", "app.jar" ]
