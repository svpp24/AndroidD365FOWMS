# Dev-only X++ tools - never part of the shipped HSMobileOps model

- `HSMobileOpsLoadTestItems.xml` - creates (and deletes) load-test items LT-0000001... in a test
  company. Kept on the dev VM so its test items can still be removed there. Copy it into a model
  only on a test environment, and never deploy it to a customer.

Removed from the dev VM on 16 Nov 2026 (left-over test classes): CountAssortmentProbe,
CountGuardProbe, CountHazardRequestTest, CreateVariantTestPO, LoadSim, SkuSnapshotProbe,
TestDataGenerator, TestDataTask, TestUsersSeed, WorkspaceScopeTest.
