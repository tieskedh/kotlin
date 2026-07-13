@echo off
set _KOTLIN_COMPILER=org.jetbrains.kotlin.cli.dotnet.K2DotNetCompiler
call %~dps0kotlinc.bat %*
