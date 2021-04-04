#!/bin/bash


SECONDS=0

for i in {1..250}
do
  curl http://localhost:8080/mocks/29386
  curl http://localhost:8080/mocks/29385
  echo ""
done

duration=$SECONDS
echo "$(($duration / 60)) minutes and $(($duration % 60)) seconds elapsed."
