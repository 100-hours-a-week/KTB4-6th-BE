package com.backend.meety;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MeetyApplication {

	public static void main(String[] args) {
		SpringApplication.run(MeetyApplication.class, args);
	}

}
