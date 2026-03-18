package com.zyra;

import com.zyra.tcp.TCPServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ZyraDbApplication {

	public static void main(String[] args) {
		SpringApplication.run(ZyraDbApplication.class, args);

		// Start TCP Server manually
		TCPServer server = new TCPServer(6379);
		server.start();
	}
}