# Subscription Stack

## Overview

The Subscription Stack provides the infrastructure for managing user subscriptions in the Synapse-D platform. It integrates with Stripe for payment processing and uses DynamoDB for storing subscription data. The stack includes Lambda functions for creating subscriptions, verifying subscriptions, and handling Stripe webhooks.

## Components

### Lambda Functions

The stack includes several Lambda functions:

#### CreateSubscriptionHandler

- Creates a Stripe checkout session for subscription payment
- Stores subscription information in DynamoDB
- Generates initial subscription proof
- Returns checkout URL to the client

#### VerifySubscriptionHandler

- Verifies user subscriptions using DID and proof
- Checks subscription status and expiration
- Generates new subscription proofs
- Returns verification status to the client

#### WebhookHandler

- Handles Stripe webhook events
- Updates subscription status in DynamoDB
- Processes subscription lifecycle events (created, updated, cancelled)
- Implements webhook signature verification

### DynamoDB Tables

The stack creates DynamoDB tables for:
- Subscriptions storage
- Subscription proofs storage
- User subscription status

### API Gateway

The API Gateway provides REST APIs for:
- Creating subscriptions
- Verifying subscriptions
- Handling Stripe webhooks

### IAM Roles and Policies

IAM roles and policies are created for:
- Lambda function execution
- DynamoDB access
- CloudWatch Logs access
- API Gateway integration

## Architecture

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │     │ API Gateway │     │   Lambda    │
│             │◄───►│             │◄───►│  Functions  │
└─────────────┘     └─────────────┘     └─────────────┘
                                              ▲
                                              │
                                       ┌─────────────┐
                                       │  DynamoDB   │
                                       │  (Tables)   │
                                       └─────────────┘
                                              ▲
                                              │
                                       ┌─────────────┐
                                       │   Stripe    │
                                       │             │
                                       └─────────────┘
```

## Subscription Flow

1. Client requests to create a subscription
2. CreateSubscriptionHandler creates a Stripe checkout session
3. Client redirects to Stripe checkout
4. User completes payment on Stripe
5. Stripe sends webhook event to WebhookHandler
6. WebhookHandler updates subscription status in DynamoDB
7. Client verifies subscription using VerifySubscriptionHandler
8. VerifySubscriptionHandler generates subscription proof
9. Client uses proof for authenticated requests

## Zero-Knowledge Proof Verification

The subscription system uses zero-knowledge proofs for privacy-preserving authentication:
- Proofs are generated when subscriptions are verified
- Proofs are stored in DynamoDB with expiration times
- Proofs are verified by the Relay Server and other services
- Proofs do not reveal subscription details

## Security Considerations

- All communication is encrypted using TLS
- Stripe webhook signatures are verified
- DynamoDB tables are encrypted at rest
- IAM roles follow the principle of least privilege
- API Gateway requests are validated and rate-limited
- Sensitive data is stored in AWS Secrets Manager

## Integration with Other Stacks

The Subscription Stack integrates with:
- Security Stack: For IAM roles and security groups
- Relay Stack: For subscription verification
- Authentication Stack: For user identity management
- Logging Stack: For operational logging

## Best Practices

1. Implement proper error handling and retries
2. Use connection pooling for DynamoDB access
3. Implement proper logging for troubleshooting
4. Use environment variables for configuration
5. Implement proper testing for the Lambda functions
6. Monitor API Gateway and Lambda metrics
7. Implement proper security controls
8. Handle Stripe webhook idempotency 