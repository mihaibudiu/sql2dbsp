#!/bin/bash

FILE=$1
cd ../../dbsp
FILEPATH=../sql2dbsp/SQL-compiler/${FILE}
cargo run -p dataflow-jit --bin dataflow-jit --features binary -- validate ${FILEPATH}
#cargo run -p dataflow-jit --features binary -- --print-schema ${FILEPATH}
