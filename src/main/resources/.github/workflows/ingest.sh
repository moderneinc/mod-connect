#! /bin/bash

while IFS="," read -r repoName branch javaVersion style buildAction skip skipReason
do
  if test "$repoName" != "" && test "$repoName" != "repoName";
  then
    if [[ $branch = "" ]];
    then
      branch="main"
    fi
    if [[ $javaVersion = "" ]];
    then
      javaVersion="11"
    fi
    echo "$repoName $branch"
    if test "$skip" = "false" || test "$skip" = "";
    then
      curl -X POST -H "Accept: application/vnd.github+json" -H "Authorization: Bearer $GH_PAT" -H "X-GitHub-Api-Version: 2022-11-28" $1/repos/$2/dispatches \
        --data "{ \"event_type\":\"moderne-ingest\", \"client_payload\":{ \"repo\": \"$repoName\", \"branch\": \"$branch\", \"javaVersion\": \"$javaVersion\", \"desiredStyle\": \"$style\", \"additionalBuildArgs\": \"$buildAction\"} }"
    fi
  fi
done < <(cat repos.csv | tr -d '\r'; echo;)