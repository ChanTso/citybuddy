FROM docker.elastic.co/elasticsearch/elasticsearch:8.19.8@sha256:1b6a877f18352510860ee065f01472bd37d33ac5eb1d943e0b9ed366b149638c

ARG IK_PLUGIN_URL=https://get.infini.cloud/elasticsearch/analysis-ik/8.19.8
ARG IK_PLUGIN_SHA256=0afb783891e7a5443ef45b8964a2cb8d6ac2421827f94c587d1827936f00b81d

RUN curl --fail --silent --show-error --location "$IK_PLUGIN_URL" \
      --output /tmp/elasticsearch-analysis-ik.zip \
    && echo "$IK_PLUGIN_SHA256  /tmp/elasticsearch-analysis-ik.zip" | sha256sum --check - \
    && bin/elasticsearch-plugin install --batch file:///tmp/elasticsearch-analysis-ik.zip \
    && rm /tmp/elasticsearch-analysis-ik.zip
