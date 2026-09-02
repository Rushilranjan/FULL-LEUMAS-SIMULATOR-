################################################################################
# Automatically-generated file. Do not edit!
# Toolchain: GNU Tools for STM32 (14.3.rel1)
################################################################################

# Add inputs and outputs from these tool invocations to the build variables 
C_SRCS += \
../App/app.c \
../App/ble.c \
../App/button.c \
../App/config.c \
../App/led_cmd.c \
../App/led_fx.c \
../App/led_strip.c \
../App/module.c \
../App/seq_leds.c \
../App/solid_seq.c \
../App/timebase.c 

OBJS += \
./App/app.o \
./App/ble.o \
./App/button.o \
./App/config.o \
./App/led_cmd.o \
./App/led_fx.o \
./App/led_strip.o \
./App/module.o \
./App/seq_leds.o \
./App/solid_seq.o \
./App/timebase.o 

C_DEPS += \
./App/app.d \
./App/ble.d \
./App/button.d \
./App/config.d \
./App/led_cmd.d \
./App/led_fx.d \
./App/led_strip.d \
./App/module.d \
./App/seq_leds.d \
./App/solid_seq.d \
./App/timebase.d 

# Each subdirectory must supply rules for building sources it contributes
App/%.o App/%.su App/%.cyclo: ../App/%.c App/subdir.mk
	arm-none-eabi-gcc "$<" -mcpu=cortex-m7 -std=gnu11 -g3 -DDEBUG -DUSE_HAL_DRIVER -DSTM32F767xx -c -I../Core/Inc -I../App -I../Drivers/STM32F7xx_HAL_Driver/Inc -I../Drivers/STM32F7xx_HAL_Driver/Inc/Legacy -I../Drivers/CMSIS/Device/ST/STM32F7xx/Include -I../Drivers/CMSIS/Include -O0 -ffunction-sections -fdata-sections -Wall -fstack-usage -fcyclomatic-complexity -MMD -MP -MF"$(@:%.o=%.d)" -MT"$@" --specs=nano.specs -mfpu=fpv5-d16 -mfloat-abi=hard -mthumb -o "$@"

clean: clean-App

clean-App:
	-$(RM) ./App/app.cyclo ./App/app.d ./App/app.o ./App/app.su ./App/ble.cyclo ./App/ble.d ./App/ble.o ./App/ble.su ./App/button.cyclo ./App/button.d ./App/button.o ./App/button.su ./App/config.cyclo ./App/config.d ./App/config.o ./App/config.su ./App/led_cmd.cyclo ./App/led_cmd.d ./App/led_cmd.o ./App/led_cmd.su ./App/led_fx.cyclo ./App/led_fx.d ./App/led_fx.o ./App/led_fx.su ./App/led_strip.cyclo ./App/led_strip.d ./App/led_strip.o ./App/led_strip.su ./App/module.cyclo ./App/module.d ./App/module.o ./App/module.su ./App/seq_leds.cyclo ./App/seq_leds.d ./App/seq_leds.o ./App/seq_leds.su ./App/solid_seq.cyclo ./App/solid_seq.d ./App/solid_seq.o ./App/solid_seq.su ./App/timebase.cyclo ./App/timebase.d ./App/timebase.o ./App/timebase.su 

.PHONY: clean-App
