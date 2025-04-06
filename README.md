# Synapsed AWS Infrastructure

This project contains the AWS CDK infrastructure code for the Synapsed platform, implemented in Java.

## Prerequisites

- Java 21 or later
- Maven 3.8.1 or later
- AWS CLI configured with appropriate credentials
- AWS CDK CLI installed (`npm install -g aws-cdk`)

## Project Structure

```
synapsed-aws/
├── src/
│   ├── main/
│   │   └── java/
│   │       └── me/
│   │           └── synapsed/
│   │               └── aws/
│   │                   ├── SynapsedApp.java
│   │                   └── SynapsedStack.java
│   └── test/
│       └── java/
│           └── me/
│               └── synapsed/
│                   └── aws/
│                       └── SynapsedStackTest.java
└── pom.xml
```

## Getting Started

1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/synapsed-aws.git
   cd synapsed-aws
   ```

2. Build the project:
   ```bash
   mvn clean package
   ```

3. Run the tests:
   ```bash
   mvn test
   ```

4. Deploy the infrastructure:
   ```bash
   cdk deploy
   ```

## Development

The project uses AWS CDK to define infrastructure as code. The main components are:

- `SynapsedApp.java`: The entry point of the CDK application
- `SynapsedStack.java`: The main stack containing AWS resources
- `SynapsedStackTest.java`: Tests for the infrastructure code

## Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details. 