package com.jtyll.pricerapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PricerApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(PricerApiApplication.class, args);
	}

}
