package org.testcontainers.containers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.command.CreateContainerCmdImpl;
import com.github.dockerjava.core.command.InspectContainerCmdImpl;
import com.github.dockerjava.core.command.ListContainersCmdImpl;
import com.github.dockerjava.core.command.StartContainerCmdImpl;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.junit.Assume;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.Parameterized;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.rnorth.visibleassertions.VisibleAssertions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.utility.TestcontainersConfiguration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@RunWith(Enclosed.class)
public class ReusabilityUnitTests {

    static final CompletableFuture<String> IMAGE_FUTURE = CompletableFuture.completedFuture(
        TestcontainersConfiguration.getInstance().getTinyImage()
    );

    @RunWith(Parameterized.class)
    @RequiredArgsConstructor
    @FieldDefaults(makeFinal = true)
    public static class CanBeReusedTest {

        @Parameterized.Parameters(name = "{0}")
        public static Object[][] data() {
            return new Object[][] {
                { "generic", new GenericContainer(IMAGE_FUTURE), true },
                { "anonymous generic", new GenericContainer(IMAGE_FUTURE) {}, true },
                { "custom", new CustomContainer(), true },
                { "anonymous custom", new CustomContainer() {}, true },
                { "custom with containerIsCreated", new CustomContainerWithContainerIsCreated(), false },
            };
        }

        String name;

        GenericContainer container;

        boolean reusable;

        @Test
        public void shouldBeReusable() {
            if (reusable) {
                VisibleAssertions.assertTrue("Is reusable", container.canBeReused());
            } else {
                VisibleAssertions.assertFalse("Is not reusable", container.canBeReused());
            }
        }

        static class CustomContainer extends GenericContainer {
            CustomContainer() {
                super(IMAGE_FUTURE);
            }
        }

        static class CustomContainerWithContainerIsCreated extends GenericContainer {

            CustomContainerWithContainerIsCreated() {
                super(IMAGE_FUTURE);
            }

            @Override
            protected void containerIsCreated(String containerId) {
                super.containerIsCreated(containerId);
            }
        }
    }

    @RunWith(BlockJUnit4ClassRunner.class)
    @FieldDefaults(makeFinal = true)
    public static class HashTest {

        StartupCheckStrategy startupCheckStrategy = new StartupCheckStrategy() {
            @Override
            public StartupStatus checkStartupState(DockerClient dockerClient, String containerId) {
                return StartupStatus.SUCCESSFUL;
            }
        };

        WaitStrategy waitStrategy = new AbstractWaitStrategy() {
            @Override
            protected void waitUntilReady() {

            }
        };

        DockerClient client = Mockito.mock(DockerClient.class);

        GenericContainer container = new GenericContainer(IMAGE_FUTURE) {
            {
                withNetworkMode("none"); // to disable the port forwarding
                withStartupCheckStrategy(startupCheckStrategy);
                waitingFor(waitStrategy);
                withReuse(true);
                dockerClient = client;
            }

            @Override
            public void copyFileToContainer(MountableFile mountableFile, String containerPath) {
                // NOOP
            }
        };

        @Test
        public void shouldStartIfListReturnsEmpty() {
            String containerId = randomContainerId();
            when(client.createContainerCmd(any())).then(createContainerAnswer(containerId));
            when(client.listContainersCmd()).then(listContainersAnswer());
            when(client.startContainerCmd(containerId)).then(startContainerAnswer());
            when(client.inspectContainerCmd(containerId)).then(inspectContainerAnswer());

            container.start();

            Mockito.verify(client, Mockito.atLeastOnce()).startContainerCmd(containerId);
        }

        @Test
        public void shouldReuseIfListReturnsID() {
            // TODO mock TestcontainersConfiguration
            Assume.assumeTrue("supports reuse", TestcontainersConfiguration.getInstance().environmentSupportsReuse());
            when(client.createContainerCmd(any())).then(createContainerAnswer(randomContainerId()));
            String existingContainerId = randomContainerId();
            when(client.listContainersCmd()).then(listContainersAnswer(existingContainerId));
            when(client.inspectContainerCmd(existingContainerId)).then(inspectContainerAnswer());

            container.start();

            Mockito.verify(client, Mockito.never()).startContainerCmd(existingContainerId);
        }

        @Test
        public void shouldSetLabelsIfEnvironmentDoesNotSupportReuse() {
            // TODO mock TestcontainersConfiguration
            Assume.assumeFalse("does not support reuse", TestcontainersConfiguration.getInstance().environmentSupportsReuse());
            AtomicReference<CreateContainerCmd> commandRef = new AtomicReference<>();
            String containerId = randomContainerId();
            when(client.createContainerCmd(any())).then(createContainerAnswer(containerId, commandRef::set));
            when(client.startContainerCmd(containerId)).then(startContainerAnswer());
            when(client.inspectContainerCmd(containerId)).then(inspectContainerAnswer());

            container.start();

            assertThat(commandRef)
                .isNotNull()
                .satisfies(command -> {
                    assertThat(command.get().getLabels())
                        .containsKeys(DockerClientFactory.TESTCONTAINERS_SESSION_ID_LABEL);
                });
        }

        @Test
        public void shouldSetCopiedFilesHashLabel() {
            // TODO mock TestcontainersConfiguration
            Assume.assumeTrue("supports reuse", TestcontainersConfiguration.getInstance().environmentSupportsReuse());
            AtomicReference<CreateContainerCmd> commandRef = new AtomicReference<>();
            String containerId = randomContainerId();
            when(client.createContainerCmd(any())).then(createContainerAnswer(containerId, commandRef::set));
            when(client.listContainersCmd()).then(listContainersAnswer());
            when(client.startContainerCmd(containerId)).then(startContainerAnswer());
            when(client.inspectContainerCmd(containerId)).then(inspectContainerAnswer());

            container.start();

            assertThat(commandRef).isNotNull();
            assertThat(commandRef.get().getLabels())
                .containsKeys(GenericContainer.COPIED_FILES_HASH_LABEL);
        }

        @Test
        public void shouldHashCopiedFiles() {
            // TODO mock TestcontainersConfiguration
            Assume.assumeTrue("supports reuse", TestcontainersConfiguration.getInstance().environmentSupportsReuse());
            AtomicReference<CreateContainerCmd> commandRef = new AtomicReference<>();
            String containerId = randomContainerId();
            when(client.createContainerCmd(any())).then(createContainerAnswer(containerId, commandRef::set));
            when(client.listContainersCmd()).then(listContainersAnswer());
            when(client.startContainerCmd(containerId)).then(startContainerAnswer());
            when(client.inspectContainerCmd(containerId)).then(inspectContainerAnswer());

            container.start();

            assertThat(commandRef).isNotNull();

            Map<String, String> labels = commandRef.get().getLabels();
            assertThat(labels).containsKeys(GenericContainer.COPIED_FILES_HASH_LABEL);

            String oldHash = labels.get(GenericContainer.COPIED_FILES_HASH_LABEL);

            // Simulate stop
            container.containerId = null;

            container.withCopyFileToContainer(
                MountableFile.forClasspathResource("test_copy_to_container.txt"),
                "/foo/bar"
            );
            container.start();

            assertThat(commandRef.get().getLabels()).hasEntrySatisfying(GenericContainer.COPIED_FILES_HASH_LABEL, newHash -> {
                assertThat(newHash).as("new hash").isNotEqualTo(oldHash);
            });
        }

        private String randomContainerId() {
            return UUID.randomUUID().toString();
        }

        private Answer<ListContainersCmd> listContainersAnswer(String... ids) {
            return invocation -> {
                ListContainersCmd.Exec exec = command -> {
                    return new ObjectMapper().convertValue(
                        Stream.of(ids)
                            .map(id -> Collections.singletonMap("Id", id))
                            .collect(Collectors.toList()),
                        new TypeReference<List<Container>>() {}
                    );
                };
                return new ListContainersCmdImpl(exec);
            };
        }

        private Answer<CreateContainerCmd> createContainerAnswer(String containerId) {
            return createContainerAnswer(containerId, command -> {});
        }

        private Answer<CreateContainerCmd> createContainerAnswer(String containerId, Consumer<CreateContainerCmd> cmdConsumer) {
            return invocation -> {
                CreateContainerCmd.Exec exec = command -> {
                    cmdConsumer.accept(command);
                    CreateContainerResponse response = new CreateContainerResponse();
                    response.setId(containerId);
                    return response;
                };
                return new CreateContainerCmdImpl(exec, null, "image:latest");
            };
        }

        private Answer<StartContainerCmd> startContainerAnswer() {
            return invocation -> {
                StartContainerCmd.Exec exec = command -> {
                    return null;
                };
                return new StartContainerCmdImpl(exec, invocation.getArgument(0));
            };
        }

        private Answer<InspectContainerCmd> inspectContainerAnswer() {
            return invocation -> {
                InspectContainerCmd.Exec exec = command -> {
                    return new InspectContainerResponse();
                };
                return new InspectContainerCmdImpl(exec, invocation.getArgument(0));
            };
        }

    }

    @RunWith(BlockJUnit4ClassRunner.class)
    @FieldDefaults(makeFinal = true)
    public static class CopyFilesHashTest {
        GenericContainer<?> container = new GenericContainer(IMAGE_FUTURE);

        @Test
        public void empty() {
            assertThat(container.hashCopiedFiles()).isNotNull();
        }

        @Test
        public void oneFile() {
            long emptyHash = container.hashCopiedFiles().getValue();

            container.withCopyFileToContainer(
                MountableFile.forClasspathResource("test_copy_to_container.txt"),
                "/foo/bar"
            );

            assertThat(container.hashCopiedFiles().getValue()).isNotEqualTo(emptyHash);
        }

        @Test
        public void differentPath() {
            MountableFile mountableFile = MountableFile.forClasspathResource("test_copy_to_container.txt");
            container.withCopyFileToContainer(mountableFile, "/foo/bar");

            long hash1 = container.hashCopiedFiles().getValue();

            container.getCopyToFileContainerPathMap().clear();

            container.withCopyFileToContainer(mountableFile, "/foo/baz");

            assertThat(container.hashCopiedFiles().getValue()).isNotEqualTo(hash1);
        }

        @Test
        public void detectsChangesInFile() throws Exception {
            Path path = File.createTempFile("reusable_test", ".txt").toPath();
            MountableFile mountableFile = MountableFile.forHostPath(path);
            container.withCopyFileToContainer(mountableFile, "/foo/bar");

            long hash1 = container.hashCopiedFiles().getValue();

            Files.write(path, UUID.randomUUID().toString().getBytes());

            assertThat(container.hashCopiedFiles().getValue()).isNotEqualTo(hash1);
        }

        @Test
        public void multipleFiles() {
            container.withCopyFileToContainer(
                MountableFile.forClasspathResource("test_copy_to_container.txt"),
                "/foo/bar"
            );
            long hash1 = container.hashCopiedFiles().getValue();

            container.withCopyFileToContainer(
                MountableFile.forClasspathResource("mappable-resource/test-resource.txt"),
                "/foo/baz"
            );

            assertThat(container.hashCopiedFiles().getValue()).isNotEqualTo(hash1);
        }

        @Test
        public void folder() throws Exception {
            long emptyHash = container.hashCopiedFiles().getValue();

            Path tempDirectory = Files.createTempDirectory("reusable_test");
            MountableFile mountableFile = MountableFile.forHostPath(tempDirectory);
            container.withCopyFileToContainer(mountableFile, "/foo/bar/");

            assertThat(container.hashCopiedFiles().getValue()).isNotEqualTo(emptyHash);
        }

        @Test
        public void changesInFolder() throws Exception {
            Path tempDirectory = Files.createTempDirectory("reusable_test");
            MountableFile mountableFile = MountableFile.forHostPath(tempDirectory);
            assertThat(new File(mountableFile.getResolvedPath())).isDirectory();
            container.withCopyFileToContainer(mountableFile, "/foo/bar/");

            long hash1 = container.hashCopiedFiles().getValue();

            Path fileInFolder = Files.createFile(
                // Create file in the sub-folder
                Files.createDirectory(tempDirectory.resolve("sub")).resolve("test.txt")
            );
            assertThat(fileInFolder).exists();
            Files.write(fileInFolder, UUID.randomUUID().toString().getBytes());

            assertThat(container.hashCopiedFiles().getValue()).isNotEqualTo(hash1);
        }

        @Test
        public void folderAndFile() throws Exception {
            Path tempDirectory = Files.createTempDirectory("reusable_test");
            MountableFile mountableFile = MountableFile.forHostPath(tempDirectory);
            assertThat(new File(mountableFile.getResolvedPath())).isDirectory();
            container.withCopyFileToContainer(mountableFile, "/foo/bar/");

            long hash1 = container.hashCopiedFiles().getValue();

            container.withCopyFileToContainer(
                MountableFile.forClasspathResource("test_copy_to_container.txt"),
                "/foo/baz"
            );

            assertThat(container.hashCopiedFiles().getValue()).isNotEqualTo(hash1);
        }
    }
}
