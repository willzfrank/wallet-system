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

		DoTransDto transfer = new DoTransDto("TXN-1001", sourceAccount, destinationAccount, new BigDecimal("40.00"));

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

		DoTransDto transfer = new DoTransDto("TXN-1002", sourceAccount, destinationAccount, new BigDecimal("20.00"));

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

		DoTransDto transfer = new DoTransDto("TXN-1003", sourceAccount, destinationAccount, BigDecimal.ZERO);

		mockMvc.perform(post("/api/wallet/transfer")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(transfer)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void shouldRejectAmountWithMoreThanTwoDecimalPlaces() throws Exception {
		String sourceAccount = createUser("scale-a@example.com", new BigDecimal("10.00"));
		String destinationAccount = createUser("scale-b@example.com", new BigDecimal("5.00"));
		DoTransDto transfer = new DoTransDto("TXN-SCALE-1", sourceAccount, destinationAccount, new BigDecimal("1.234"));

		mockMvc.perform(post("/api/wallet/transfer")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(transfer)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Amount must have at most 2 decimal places"));
	}

	@Test
	void shouldReturnNotFoundForMissingAccount() throws Exception {
		mockMvc.perform(get("/api/wallet/accounts/{accountNumber}/balance", "ACC-MISSING"))
				.andExpect(status().isNotFound());
	}

	@Test
	void shouldRejectDuplicateEmailCreation() throws Exception {
		CreateUserAccountRequest request = new CreateUserAccountRequest("dupe@example.com", new BigDecimal("10.00"));

		mockMvc.perform(post("/api/wallet/users")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/wallet/users")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("User already exists for email: dupe@example.com"));
	}

	@Test
	void shouldRejectTransferToSameAccount() throws Exception {
		String accountNumber = createUser("self@example.com", new BigDecimal("50.00"));
		DoTransDto transfer = new DoTransDto("TXN-1004", accountNumber, accountNumber, new BigDecimal("10.00"));

		mockMvc.perform(post("/api/wallet/transfer")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(transfer)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Source and destination accounts cannot be the same"));
	}

	@Test
	void shouldRejectBlankEmailOnCreateUser() throws Exception {
		CreateUserAccountRequest request = new CreateUserAccountRequest(" ", new BigDecimal("10.00"));

		mockMvc.perform(post("/api/wallet/users")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void shouldRejectBlankSourceAccountOnTransfer() throws Exception {
		String destinationAccount = createUser("valid-destination@example.com", new BigDecimal("15.00"));
		DoTransDto transfer = new DoTransDto("TXN-1005", " ", destinationAccount, new BigDecimal("5.00"));

		mockMvc.perform(post("/api/wallet/transfer")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(transfer)))
				.andExpect(status().isBadRequest());
	}

	@Test
	void shouldBeIdempotentForDuplicateTransactionReference() throws Exception {
		String sourceAccount = createUser("idem-source@example.com", new BigDecimal("100.00"));
		String destinationAccount = createUser("idem-destination@example.com", new BigDecimal("10.00"));

		DoTransDto transfer = new DoTransDto("TXN-IDEMPOTENT-1", sourceAccount, destinationAccount, new BigDecimal("30.00"));

		mockMvc.perform(post("/api/wallet/transfer")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(transfer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fromBalance").value(70.00))
				.andExpect(jsonPath("$.toBalance").value(40.00));

		// Simulates partial failure case: first transfer committed but client retries
		// because it did not receive response (timeout/network). No second debit should happen.
		mockMvc.perform(post("/api/wallet/transfer")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(transfer)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fromBalance").value(70.00))
				.andExpect(jsonPath("$.toBalance").value(40.00));
	}

	@Test
	void shouldRejectReusedTransactionReferenceWithDifferentPayload() throws Exception {
		String sourceAccount = createUser("guard-source@example.com", new BigDecimal("100.00"));
		String destinationAccount = createUser("guard-destination@example.com", new BigDecimal("10.00"));
		String otherDestination = createUser("guard-other@example.com", new BigDecimal("5.00"));

		DoTransDto original = new DoTransDto("TXN-GUARD-1", sourceAccount, destinationAccount, new BigDecimal("20.00"));
		mockMvc.perform(post("/api/wallet/transfer")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(original)))
				.andExpect(status().isOk());

		// Same transaction reference but different destination/amount must be rejected.
		DoTransDto mutated = new DoTransDto("TXN-GUARD-1", sourceAccount, otherDestination, new BigDecimal("25.00"));
		mockMvc.perform(post("/api/wallet/transfer")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(mutated)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Transaction reference already used with different payload"));
	}

	@Test
	void shouldHandleAtLeastOnceDeliveryWithoutDuplicateDebit() throws Exception {
		String sourceAccount = createUser("delivery-source@example.com", new BigDecimal("90.00"));
		String destinationAccount = createUser("delivery-destination@example.com", new BigDecimal("10.00"));
		DoTransDto transfer = new DoTransDto("TXN-DELIVERY-1", sourceAccount, destinationAccount, new BigDecimal("20.00"));

		// Simulate repeated delivery (client retry / gateway retry / message redelivery).
		for (int attempt = 0; attempt < 5; attempt++) {
			mockMvc.perform(post("/api/wallet/transfer")
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(transfer)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.fromBalance").value(70.00))
					.andExpect(jsonPath("$.toBalance").value(30.00));
		}

		mockMvc.perform(get("/api/wallet/accounts/{accountNumber}/balance", sourceAccount))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balance").value(70.00));

		mockMvc.perform(get("/api/wallet/accounts/{accountNumber}/balance", destinationAccount))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balance").value(30.00));
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
