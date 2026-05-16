@echo off

cd /d "D:\EngineerEnv\rocketmq-all-5.5.0-bin-release\bin"

mqbroker.cmd -n 127.0.0.1:9876 -c ../conf/broker.conf

pause