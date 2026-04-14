package com.example.test;

import com.example.test.dto.CreateUserAccountRequest;
import com.example.test.dto.DoTransDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TestApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void contextLoads() {
	}

	@Test
	void shouldCreateUserAndTransferFundsSuccessfully() throws Exception {
		String sourceAccount = createUser("source@example.com", new BigDecimal("100.00"));
		String destinationAccount = createUser("destination@example.com", new BigDecimal("25.00"));

		DoTransDto transfer = new DoTransDto(sourceAccount, destinationAccount, new BigDecimal("40.00"));

		mockMvc.perform(post("/api/wallet/transfer")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(transfer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fromAccount").value(sourceAccount))
				.andExpect(jsonPath("$.toAccount").value(destinationAccount))
				.andExpect(jsonPath("$.fromBalance").value(60.00))
				.andExpect(jsonPath("$.toBalance").value(65.00));

		mockMvc.perform(get("/api/wallet/accounts/{accountNumber}/balance", sourceAccount))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balance").value(60.00));
	}

	@Test
	void shouldRejectTransferWhenInsufficientFunds() throws Exception {
		String sourceAccount = createUser("poor@example.com", new BigDecimal("10.00"));
		String destinationAccount = createUser("rich@example.com", new BigDecimal("5.00"));

		DoTransDto transfer = new DoTransDto(sourceAccount, destinationAccount, new BigDecimal("20.00"));

		mockMvc.perform(post("/api/wallet/transfer")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(transfer)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Insufficient funds in account: " + sourceAccount));
	}

	@Test
	void shouldRejectInvalidTransferAmount() throws Exception {
		String sourceAccount = createUser("amount-a@example.com", new BigDecimal("10.00"));
		String destinationAccount = createUser("amount-b@example.com", new BigDecimal("5.00"));

		DoTransDto transfer = new DoTransDto(sourceAccount, destinationAccount, BigDecimal.ZERO);

		mockMvc.perform(post("/api/wallet/transfer")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(transfer)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void shouldReturnNotFoundForMissingAccount() throws Exception {
		mockMvc.perform(get("/api/wallet/accounts/{accountNumber}/balance", "ACC-MISSING"))
				.andExpect(status().isNotFound());
	}

	private String createUser(String email, BigDecimal initialBalance) throws Exception {
		CreateUserAccountRequest request = new CreateUserAccountRequest(email, initialBalance);
		String response = mockMvc.perform(post("/api/wallet/users")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();

		JsonNode body = objectMapper.readTree(response);
		return body.get("accountNumber").asText();
	}
}
