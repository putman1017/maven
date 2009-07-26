package org.apache.maven.model.building;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Repository;
import org.apache.maven.model.composition.DependencyManagementImporter;
import org.apache.maven.model.inheritance.InheritanceAssembler;
import org.apache.maven.model.interpolation.ModelInterpolationException;
import org.apache.maven.model.interpolation.ModelInterpolator;
import org.apache.maven.model.io.ModelParseException;
import org.apache.maven.model.io.ModelReader;
import org.apache.maven.model.management.DependencyManagementInjector;
import org.apache.maven.model.management.PluginManagementInjector;
import org.apache.maven.model.normalization.ModelNormalizer;
import org.apache.maven.model.path.ModelPathTranslator;
import org.apache.maven.model.plugin.LifecycleBindingsInjector;
import org.apache.maven.model.plugin.PluginConfigurationExpander;
import org.apache.maven.model.profile.DefaultProfileActivationContext;
import org.apache.maven.model.profile.ProfileActivationContext;
import org.apache.maven.model.profile.ProfileActivationException;
import org.apache.maven.model.profile.ProfileInjector;
import org.apache.maven.model.profile.ProfileSelectionResult;
import org.apache.maven.model.profile.ProfileSelector;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.model.superpom.SuperPomProvider;
import org.apache.maven.model.validation.ModelValidationResult;
import org.apache.maven.model.validation.ModelValidator;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;

/**
 * @author Benjamin Bentmann
 */
@Component( role = ModelBuilder.class )
public class DefaultModelBuilder
    implements ModelBuilder
{

    @Requirement
    private ModelReader modelReader;

    @Requirement
    private ModelValidator modelValidator;

    @Requirement
    private ModelNormalizer modelNormalizer;

    @Requirement
    private ModelInterpolator modelInterpolator;

    @Requirement
    private ModelPathTranslator modelPathTranslator;

    @Requirement
    private SuperPomProvider superPomProvider;

    @Requirement
    private InheritanceAssembler inheritanceAssembler;

    @Requirement
    private ProfileSelector profileSelector;

    @Requirement
    private ProfileInjector profileInjector;

    @Requirement
    private PluginManagementInjector pluginManagementInjector;

    @Requirement
    private DependencyManagementInjector dependencyManagementInjector;

    @Requirement
    private DependencyManagementImporter dependencyManagementImporter;

    @Requirement
    private LifecycleBindingsInjector lifecycleBindingsInjector;

    @Requirement
    private PluginConfigurationExpander pluginConfigurationExpander;

    public ModelBuildingResult build( ModelBuildingRequest request )
        throws ModelBuildingException
    {
        DefaultModelBuildingResult result = new DefaultModelBuildingResult();

        List<ModelProblem> problems = new ArrayList<ModelProblem>();

        ProfileActivationContext profileActivationContext = getProfileActivationContext( request );

        List<Profile> activeExternalProfiles = getActiveExternalProfiles( request, profileActivationContext, problems );

        Model inputModel = readModel( request.getModelSource(), request.getPomFile(), request, problems );

        ModelData resultData = new ModelData( inputModel );

        List<ModelData> lineage = new ArrayList<ModelData>();

        for ( ModelData currentData = resultData; currentData != null; )
        {
            lineage.add( currentData );

            Model tmpModel = currentData.getModel();

            Model rawModel = ModelUtils.cloneModel( tmpModel );
            currentData.setRawModel( rawModel );

            modelNormalizer.mergeDuplicates( tmpModel, request );

            List<Profile> activePomProfiles = getActivePomProfiles( rawModel, profileActivationContext, problems );
            currentData.setActiveProfiles( activePomProfiles );

            for ( Profile activeProfile : activePomProfiles )
            {
                profileInjector.injectProfile( tmpModel, activeProfile, request );
            }

            if ( currentData == resultData )
            {
                for ( Profile activeProfile : activeExternalProfiles )
                {
                    profileInjector.injectProfile( tmpModel, activeProfile, request );
                }
            }

            configureResolver( request.getModelResolver(), tmpModel, problems );

            currentData = readParent( tmpModel, request, problems );
        }

        ModelData superData = new ModelData( getSuperModel() );
        superData.setRawModel( superData.getModel() );
        superData.setActiveProfiles( Collections.<Profile> emptyList() );
        lineage.add( superData );

        assembleInheritance( lineage, request );

        Model resultModel = resultData.getModel();

        resultModel = interpolateModel( resultModel, request, problems );
        resultData.setModel( resultModel );

        resultData.setGroupId( resultModel.getGroupId() );
        resultData.setArtifactId( resultModel.getArtifactId() );
        resultData.setVersion( resultModel.getVersion() );

        result.setProblems( problems );

        result.setEffectiveModel( resultModel );

        result.setActiveExternalProfiles( activeExternalProfiles );

        for ( ModelData currentData : lineage )
        {
            String modelId = ( currentData != superData ) ? currentData.getId() : "";

            result.addModelId( modelId );
            result.setActivePomProfiles( modelId, currentData.getActiveProfiles() );
            result.setRawModel( modelId, currentData.getRawModel() );
        }

        build( request, result );

        return result;
    }

    public ModelBuildingResult build( ModelBuildingRequest request, ModelBuildingResult result )
        throws ModelBuildingException
    {
        Model resultModel = result.getEffectiveModel();

        List<ModelProblem> problems = result.getProblems();

        modelPathTranslator.alignToBaseDirectory( resultModel, resultModel.getProjectDirectory(), request );

        pluginManagementInjector.injectBasicManagement( resultModel, request );

        fireBuildExtensionsAssembled( resultModel, request, problems );

        if ( request.isProcessPlugins() )
        {
            lifecycleBindingsInjector.injectLifecycleBindings( resultModel );
        }

        pluginManagementInjector.injectManagement( resultModel, request );

        importDependencyManagement( resultModel, request, problems );

        dependencyManagementInjector.injectManagement( resultModel, request );

        modelNormalizer.injectDefaultValues( resultModel, request );

        if ( request.isProcessPlugins() )
        {
            pluginConfigurationExpander.expandPluginConfiguration( resultModel, request );
        }

        ModelValidationResult validationResult = modelValidator.validateEffectiveModel( resultModel, request );
        addProblems( resultModel, validationResult, problems );

        if ( hasErrors( problems ) )
        {
            throw new ModelBuildingException( problems );
        }

        return result;
    }

    private Model readModel( ModelSource modelSource, File pomFile, ModelBuildingRequest request,
                             List<ModelProblem> problems )
        throws ModelBuildingException
    {
        Model model;

        if ( modelSource == null )
        {
            if ( pomFile != null )
            {
                modelSource = new FileModelSource( pomFile );
            }
            else
            {
                throw new IllegalArgumentException( "neither model source nor input file are specified" );
            }
        }

        try
        {
            boolean strict = request.getValidationLevel() >= ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_2_0;

            Map<String, Object> options =
                Collections.<String, Object> singletonMap( ModelReader.IS_STRICT, Boolean.valueOf( strict ) );

            model = modelReader.read( modelSource.getInputStream(), options );
        }
        catch ( ModelParseException e )
        {
            problems.add( new ModelProblem( "Non-parseable POM " + modelSource.getLocation() + ": " + e.getMessage(),
                                            ModelProblem.Severity.FATAL, modelSource.getLocation(), e ) );
            throw new ModelBuildingException( problems );
        }
        catch ( IOException e )
        {
            problems.add( new ModelProblem( "Non-readable POM " + modelSource.getLocation() + ": " + e.getMessage(),
                                            ModelProblem.Severity.FATAL, modelSource.getLocation(), e ) );
            throw new ModelBuildingException( problems );
        }

        model.setPomFile( pomFile );

        ModelValidationResult validationResult = modelValidator.validateRawModel( model, request );
        addProblems( model, validationResult, problems );

        return model;
    }

    private boolean hasErrors( List<ModelProblem> problems )
    {
        if ( problems != null )
        {
            for ( ModelProblem problem : problems )
            {
                if ( ModelProblem.Severity.ERROR.compareTo( problem.getSeverity() ) >= 0 )
                {
                    return true;
                }
            }
        }

        return false;
    }

    private void addProblems( Model model, ModelValidationResult result, List<ModelProblem> problems )
    {
        if ( !result.getWarnings().isEmpty() || !result.getErrors().isEmpty() )
        {
            String source = toSourceHint( model );

            for ( String message : result.getWarnings() )
            {
                problems.add( new ModelProblem( "Invalid POM " + source + ": " + message,
                                                ModelProblem.Severity.WARNING, source ) );
            }

            for ( String message : result.getErrors() )
            {
                problems.add( new ModelProblem( "Invalid POM " + source + ": " + message, ModelProblem.Severity.ERROR,
                                                source ) );
            }
        }
    }

    private ProfileActivationContext getProfileActivationContext( ModelBuildingRequest request )
    {
        ProfileActivationContext context = new DefaultProfileActivationContext();

        context.setActiveProfileIds( request.getActiveProfileIds() );
        context.setInactiveProfileIds( request.getInactiveProfileIds() );
        context.setSystemProperties( request.getSystemProperties() );
        context.setUserProperties( request.getUserProperties() );
        context.setProjectDirectory( ( request.getPomFile() != null ) ? request.getPomFile().getParentFile() : null );

        return context;
    }

    private List<Profile> getActiveExternalProfiles( ModelBuildingRequest request, ProfileActivationContext context,
                                                     List<ModelProblem> problems )
    {
        ProfileSelectionResult result = profileSelector.getActiveProfiles( request.getProfiles(), context );

        for ( ProfileActivationException e : result.getActivationExceptions() )
        {
            problems.add( new ModelProblem( "Invalid activation condition for external profile "
                + e.getProfile().getId() + ": " + e.getMessage(), ModelProblem.Severity.ERROR, "(external profiles)", e ) );
        }

        return result.getActiveProfiles();
    }

    private List<Profile> getActivePomProfiles( Model model, ProfileActivationContext context,
                                                List<ModelProblem> problems )
    {
        ProfileSelectionResult result = profileSelector.getActiveProfiles( model.getProfiles(), context );

        for ( ProfileActivationException e : result.getActivationExceptions() )
        {
            problems.add( new ModelProblem( "Invalid activation condition for project profile "
                + e.getProfile().getId() + " in POM " + toSourceHint( model ) + ": " + e.getMessage(),
                                            ModelProblem.Severity.ERROR, toSourceHint( model ), e ) );
        }

        return result.getActiveProfiles();
    }

    private void configureResolver( ModelResolver modelResolver, Model model, List<ModelProblem> problems )
    {
        if ( modelResolver == null )
        {
            return;
        }

        List<Repository> repositories = model.getRepositories();
        Collections.reverse( repositories );

        for ( Repository repository : repositories )
        {
            try
            {
                modelResolver.addRepository( repository );
            }
            catch ( InvalidRepositoryException e )
            {
                problems.add( new ModelProblem( "Invalid repository " + repository.getId() + " in POM "
                    + toSourceHint( model ) + ": " + e.getMessage(), ModelProblem.Severity.ERROR,
                                                toSourceHint( model ), e ) );
            }
        }
    }

    private void assembleInheritance( List<ModelData> lineage, ModelBuildingRequest request )
    {
        for ( int i = lineage.size() - 2; i >= 0; i-- )
        {
            Model parent = lineage.get( i + 1 ).getModel();
            Model child = lineage.get( i ).getModel();
            inheritanceAssembler.assembleModelInheritance( child, parent, request );
        }
    }

    private Model interpolateModel( Model model, ModelBuildingRequest request, List<ModelProblem> problems )
    {
        try
        {
            Model result = modelInterpolator.interpolateModel( model, model.getProjectDirectory(), request );
            result.setPomFile( model.getPomFile() );
            return result;
        }
        catch ( ModelInterpolationException e )
        {
            problems.add( new ModelProblem( "Invalid expression in POM " + toSourceHint( model ) + ": "
                + e.getMessage(), ModelProblem.Severity.ERROR, toSourceHint( model ), e ) );

            return model;
        }
    }

    private ModelData readParent( Model childModel, ModelBuildingRequest request, List<ModelProblem> problems )
        throws ModelBuildingException
    {
        ModelData parentData;

        Parent parent = childModel.getParent();

        if ( parent != null )
        {
            String groupId = parent.getGroupId();
            String artifactId = parent.getArtifactId();
            String version = parent.getVersion();

            parentData = getCache( request.getModelCache(), groupId, artifactId, version, ModelCacheTag.RAW );

            if ( parentData == null )
            {
                parentData = readParentLocally( childModel, request, problems );

                if ( parentData == null )
                {
                    parentData = readParentExternally( childModel, request, problems );
                }

                putCache( request.getModelCache(), groupId, artifactId, version, ModelCacheTag.RAW, parentData );
            }
            else
            {
                parentData =
                    new ModelData( ModelUtils.cloneModel( parentData.getModel() ), parentData.getGroupId(),
                                   parentData.getArtifactId(), parentData.getVersion() );
            }
        }
        else
        {
            parentData = null;
        }

        return parentData;
    }

    private ModelData readParentLocally( Model childModel, ModelBuildingRequest request, List<ModelProblem> problems )
        throws ModelBuildingException
    {
        File projectDirectory = childModel.getProjectDirectory();
        if ( projectDirectory == null )
        {
            return null;
        }

        Parent parent = childModel.getParent();

        File pomFile = new File( new File( projectDirectory, parent.getRelativePath() ).toURI().normalize() );
        if ( pomFile.isDirectory() )
        {
            pomFile = new File( pomFile, "pom.xml" );
        }
        if ( !pomFile.isFile() )
        {
            return null;
        }

        Model candidateModel = readModel( null, pomFile, request, problems );

        String groupId = candidateModel.getGroupId();
        if ( groupId == null && candidateModel.getParent() != null )
        {
            groupId = candidateModel.getParent().getGroupId();
        }
        String artifactId = candidateModel.getArtifactId();
        String version = candidateModel.getVersion();
        if ( version == null && candidateModel.getParent() != null )
        {
            version = candidateModel.getParent().getVersion();
        }

        if ( groupId == null || !groupId.equals( parent.getGroupId() ) )
        {
            return null;
        }
        if ( artifactId == null || !artifactId.equals( parent.getArtifactId() ) )
        {
            return null;
        }
        if ( version == null || !version.equals( parent.getVersion() ) )
        {
            return null;
        }

        ModelData parentData = new ModelData( candidateModel, groupId, artifactId, version );

        return parentData;
    }

    private ModelData readParentExternally( Model childModel, ModelBuildingRequest request, List<ModelProblem> problems )
        throws ModelBuildingException
    {
        Parent parent = childModel.getParent();

        String groupId = parent.getGroupId();
        String artifactId = parent.getArtifactId();
        String version = parent.getVersion();

        ModelResolver modelResolver = request.getModelResolver();

        if ( modelResolver == null )
        {
            throw new IllegalArgumentException( "no model resolver provided, cannot resolve parent POM "
                + toId( groupId, artifactId, version ) + " for POM " + toSourceHint( childModel ) );
        }

        ModelSource modelSource;
        try
        {
            modelSource = modelResolver.resolveModel( groupId, artifactId, version );
        }
        catch ( UnresolvableModelException e )
        {
            problems.add( new ModelProblem( "Non-resolvable parent POM " + toId( groupId, artifactId, version )
                + " for POM " + toSourceHint( childModel ) + ": " + e.getMessage(), ModelProblem.Severity.FATAL,
                                            toSourceHint( childModel ), e ) );
            throw new ModelBuildingException( problems );
        }

        Model parentModel = readModel( modelSource, null, request, problems );

        ModelData parentData =
            new ModelData( parentModel, parent.getGroupId(), parent.getArtifactId(), parent.getVersion() );

        return parentData;
    }

    private Model getSuperModel()
    {
        return ModelUtils.cloneModel( superPomProvider.getSuperModel( "4.0.0" ) );
    }

    private void importDependencyManagement( Model model, ModelBuildingRequest request, List<ModelProblem> problems )
    {
        DependencyManagement depMngt = model.getDependencyManagement();

        if ( depMngt == null )
        {
            return;
        }

        ModelResolver modelResolver = request.getModelResolver();

        ModelBuildingRequest importRequest = null;

        List<Model> importModels = null;

        for ( Iterator<Dependency> it = depMngt.getDependencies().iterator(); it.hasNext(); )
        {
            Dependency dependency = it.next();

            if ( !"pom".equals( dependency.getType() ) || !"import".equals( dependency.getScope() ) )
            {
                continue;
            }

            it.remove();

            String groupId = dependency.getGroupId();
            String artifactId = dependency.getArtifactId();
            String version = dependency.getVersion();

            Model importModel =
                getCache( request.getModelCache(), groupId, artifactId, version, ModelCacheTag.EFFECTIVE );

            if ( importModel == null )
            {
                if ( modelResolver == null )
                {
                    throw new IllegalArgumentException( "no model resolver provided, cannot resolve import POM "
                        + toId( groupId, artifactId, version ) + " for POM " + toSourceHint( model ) );
                }

                ModelSource importSource;
                try
                {
                    importSource = modelResolver.resolveModel( groupId, artifactId, version );
                }
                catch ( UnresolvableModelException e )
                {
                    problems.add( new ModelProblem( "Non-resolvable import POM " + toId( groupId, artifactId, version )
                        + " for POM " + toSourceHint( model ) + ": " + e.getMessage(), ModelProblem.Severity.ERROR,
                                                    toSourceHint( model ), e ) );
                    continue;
                }

                if ( importRequest == null )
                {
                    importRequest = new DefaultModelBuildingRequest();
                    importRequest.setValidationLevel( ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL );
                }

                importRequest.setModelSource( importSource );
                importRequest.setModelResolver( modelResolver.newCopy() );

                ModelBuildingResult importResult;
                try
                {
                    importResult = build( importRequest );
                }
                catch ( ModelBuildingException e )
                {
                    problems.addAll( e.getProblems() );
                    continue;
                }

                problems.addAll( importResult.getProblems() );

                importModel = importResult.getEffectiveModel();

                putCache( request.getModelCache(), groupId, artifactId, version, ModelCacheTag.EFFECTIVE, importModel );
            }
            else
            {
                importModel = ModelUtils.cloneModel( importModel );
            }

            if ( importModels == null )
            {
                importModels = new ArrayList<Model>();
            }

            importModels.add( importModel );
        }

        dependencyManagementImporter.importManagement( model, importModels, request );
    }

    private <T> void putCache( ModelCache modelCache, String groupId, String artifactId, String version,
                               ModelCacheTag<T> tag, T data )
    {
        if ( modelCache != null )
        {
            modelCache.put( groupId, artifactId, version, tag.getName(), data );
        }
    }

    private <T> T getCache( ModelCache modelCache, String groupId, String artifactId, String version,
                            ModelCacheTag<T> tag )
    {
        if ( modelCache != null )
        {
            return tag.getType().cast( modelCache.get( groupId, artifactId, version, tag.getName() ) );
        }
        return null;
    }

    private void fireBuildExtensionsAssembled( Model model, ModelBuildingRequest request, List<ModelProblem> problems )
        throws ModelBuildingException
    {
        if ( request.getModelBuildingListeners().isEmpty() )
        {
            return;
        }

        ModelBuildingEvent event = new DefaultModelBuildingEvent( model, request );

        for ( ModelBuildingListener listener : request.getModelBuildingListeners() )
        {
            try
            {
                listener.buildExtensionsAssembled( event );
            }
            catch ( Exception e )
            {
                problems.add( new ModelProblem( "Invalid build extensions: " + e.getMessage(),
                                                ModelProblem.Severity.ERROR, toSourceHint( model ), e ) );
            }
        }
    }

    private String toSourceHint( Model model )
    {
        StringBuilder buffer = new StringBuilder( 192 );

        buffer.append( toId( model ) );

        File pomFile = model.getPomFile();
        if ( pomFile != null )
        {
            buffer.append( " (" ).append( pomFile ).append( ")" );
        }

        return buffer.toString();
    }

    private String toId( Model model )
    {
        String groupId = model.getGroupId();
        if ( groupId == null && model.getParent() != null )
        {
            groupId = model.getParent().getGroupId();
        }

        String artifactId = model.getArtifactId();

        String version = model.getVersion();
        if ( version == null && model.getParent() != null )
        {
            version = model.getParent().getVersion();
        }

        return toId( groupId, artifactId, version );
    }

    private String toId( String groupId, String artifactId, String version )
    {
        StringBuilder buffer = new StringBuilder( 96 );

        buffer.append( ( groupId != null ) ? groupId : "[unknown-group-id]" );
        buffer.append( ':' );
        buffer.append( ( artifactId != null ) ? artifactId : "[unknown-artifact-id]" );
        buffer.append( ':' );
        buffer.append( ( version != null ) ? version : "[unknown-version]" );

        return buffer.toString();
    }

}