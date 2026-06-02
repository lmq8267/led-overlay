CC ?= cc
APP_VERSION ?= $(shell date +%Y%m%d)-$(shell git rev-parse --short HEAD 2>/dev/null || echo unknown)
CFLAGS ?= -O2 -Wall -Wextra -pthread
CFLAGS += -DAPP_VERSION=\"$(APP_VERSION)\"
LDFLAGS ?= -pthread
STRIP ?= strip
MIN_CFLAGS ?= -Os -ffunction-sections -fdata-sections -fno-unwind-tables -fno-asynchronous-unwind-tables -Wall -Wextra -pthread
MIN_LDFLAGS ?= -pthread -Wl,--gc-sections -Wl,--as-needed

all: clean led_server
min: clean led_server_min

app_page.h: index.html
	@echo "// 自动生成，请勿手动编辑" > $@
	@echo "static const unsigned char app_page_html_data[] = {" >> $@
	@xxd -i < index.html >> $@
	@echo ", 0x00};" >> $@
	@echo "static const char *app_page_html = (const char *)app_page_html_data;" >> $@

led_server: led_server.c app_page.h
	$(CC) $(CFLAGS) -o $@ led_server.c $(LDFLAGS)
	$(STRIP) $@ || true

led_server_min: led_server.c app_page.h
	$(CC) $(MIN_CFLAGS) -DAPP_VERSION=\"$(APP_VERSION)\" -o led_server led_server.c $(MIN_LDFLAGS)
	$(STRIP) -s led_server || true

clean:
	rm -f led_server app_page.h
