package com.zyra;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "zyra.tcp.enabled=false")
class ZyradbApplicationTests {

	@Test
	void contextLoads() {
	}

}
