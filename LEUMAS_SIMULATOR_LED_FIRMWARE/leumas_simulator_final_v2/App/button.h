/**
 * button.h — external push button on EXTI with software debounce.
 * button_on_exti() is called from HAL_GPIO_EXTI_Callback; button_poll()
 * returns true exactly once per accepted press.
 */
#ifndef APP_BUTTON_H
#define APP_BUTTON_H

#include <stdbool.h>
#include <stdint.h>

void button_init(void);
void button_on_exti(uint16_t gpio_pin);   /* pass GPIO_Pin from the callback  */
bool button_poll(void);                    /* true once per debounced press    */

#endif /* APP_BUTTON_H */
