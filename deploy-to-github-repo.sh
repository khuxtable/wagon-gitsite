git clone git@github.com:keyz182/maven-repo.git /tmp/mvnrepo
mvn -DaltDeploymentRepository=snapshot-repo::default::file:/tmp/mvnrepo/snapshots clean deploy
cd /tmp/mvnrepo
git add *
git commit -a -m "Maven commit"
git push
