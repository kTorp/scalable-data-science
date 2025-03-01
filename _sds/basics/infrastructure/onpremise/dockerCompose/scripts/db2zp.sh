#!/bin/bash

set -x -v

zpDIRPATH=$localgitdbcDIRPATH/scalable-data-science
rm -rf $zpDIRPATH/zp
pushd $PINOTdir
for module in 000_1-sds-3-x-spark 000_1-sds-3-x-sql 000_2-sds-3-x-ml xtraResources 000_3-sds-3-x-st 000_4-sds-3-x-ss 000_5-sds-2-x-geo 000_6-sds-3-x-dl 000_7-sds-3-x-ddl 000_8-sds-3-x-pri 000_9-sds-3-x-trends
do
stack exec pinot -- --from databricks --to zeppelin $zpDIRPATH/$module.dbc -o $zpDIRPATH/zp 
done

rm -rf $localgitdockerComposeDIRPATH/zp
mv $zpDIRPATH/zp $localgitdockerComposeDIRPATH
popd
