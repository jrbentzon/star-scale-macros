// STAR-CCM+ macro: CouetteCell.java
// Written by STAR-CCM+ 14.06.013
package macro;

import java.util.*;
import java.io.*;
import java.nio.file.*;

import star.common.*;
import star.base.neo.*;
import star.base.report.*;

import star.meshing.*;
import star.prismmesher.*;
import star.solidmesher.*;
import star.dualmesher.*;
import star.resurfacer.*;
import star.passivescalar.*;

import star.kwturb.*;
import star.lesturb.*;
import star.rsturb.*;
import star.keturb.*;

import star.passivescalar.*;
import star.segregatedenergy.*;
import star.segregatedflow.*;
import star.material.*;
import star.turbulence.*;
import star.flow.*;
import star.metrics.*;
import star.vis.*;

// Simulation file note:
// Reactive Flow Demo
public class PhiStudy extends StarMacro {

        // Length units in [m]
        double RotorDiameter = __RotorDiameter__; // Rotor1 = 80mm, Rotor2 = 50mm

        // RPM Units [1/min]
        double RPM = __RPM__;

        // Fluid Properties
        double density = 997.561; // kg/m3
        double viscosity = 8.8871E-4; // Pa s

        // Flow rate
        double flowRate = (__FlowRateMlMin__) / 2.0 / 60.0 * 1e-6; // m3/s
        double massFlowRate = flowRate * density;

        // Concentration
        double concentrationNaSO4 = __concentrationNa2SO4__; // mol/kgw
        double concentrationBaCl2 = __concentrationBaCl2__; // mol/kgw
        String[] scalars = { "mSO4_2-", "mNa_1+", "mBa_2+", "mCl_1-", "BariteScale" };
        double D_SO4 = 2.0 * 1.06e-5 * 1e-4;
        double D_Na = 1.0 * 1.334e-5 * 1e-4;
        double D_Ba = 2.0 * 0.847e-5 * 1e-4;
        double D_Cl = 1.0 * 2.032e-5 * 1e-4;
        double molarMassH2O = 18.01528e-3; // kg / mole
        double molarMassBarite = 233.39e-3; // kg / mole
        double DensityBarite = 4480; // kg / mole
        double R_fit_pA = 1.03E-4;
        double R_fit_pB = 0.00822;
        double k1 = 1;

        // Turbulent Schmidt Number
        double TurbulentSchmidtNumber = __TurbulentSchmidtNumber__;

        // Temperature
        double Temperature = __Temperature__; // C

        // Mesh
        double RelMeshBaseSize = __MeshSize__; // - [dimensionless in terms of R_stator - R_rotor]

        // Time-Stepping
        boolean IsSteady = true;
        double targetCourant = 0.8; // adjusting timestep by RPM, RotorDiameter and MeshSize
        double TimeStep = targetCourant * 1e-3 * (1000.0 / RPM) * (RelMeshBaseSize / 20.0) * (80.0e-3 / RotorDiameter); // s

        // TurbulenceModelling
        String TurbulenceModel = "RANS-RST"; // "RANS-kOmega" / "LES" / "Laminar" / "RANS-RST" / "RANS-kEpsilon"

        // New FileName
        String uid = UUID.randomUUID().toString().substring(0, 5);
        String SimName = "Sim_" + TurbulenceModel + "_" + uid + ".sim";
        String SessionDirectory = "";

        int iterationUpdateFrequency = 5;
        int innerIterations = 1;

        boolean isReactive = false;

        public void execute() {
                Simulation simulation = getActiveSimulation();
                SessionDirectory = simulation.getSessionDir();
                StartRstReactiveSimulation();

        }

        public String getFileName() {
                return String.format("PhiTimeSeries_Rotor%s_%s_Shear.sim", getRotorDiameterString(), getRpmString());
        }

        private void saveAs(String simName) {
                Simulation simulation = getActiveSimulation();
                String SessionDirectory = simulation.getSessionDir();
                String fullPath = SessionDirectory + "/" + simName;

                try {
                        simulation.saveState(fullPath);
                } catch (Exception ex) {
                        simulation.println(ex);
                }
        }

        private String getRpmString() {
                Simulation simulation = getActiveSimulation();
                ScalarGlobalParameter sParameter = (ScalarGlobalParameter) simulation.get(GlobalParameterManager.class)
                                .getObject("RPM");
                return sParameter.getQuantity().getDefinition().replace(" ", "").replace(".0", "") + "RPM";
        }

        private String getRotorDiameterString() {
                Simulation simulation = getActiveSimulation();
                ScalarGlobalParameter sParameter = (ScalarGlobalParameter) simulation.get(GlobalParameterManager.class)
                                .getObject("RotorDiameter");
                double diameterMeters = sParameter.getQuantity().getSIValue();
                return String.format("%1.0fmm", diameterMeters * 1000);
        }

        private void StartRstReactiveSimulation() {

                swapToUnsteady();

                setTimeStep(TimeStep);
                EnableAdaptiveTimeStepping(targetCourant, targetCourant * 5);
                enableSecondOrderTimestep();
                setInnerIterationCount(innerIterations);

                // Enable passive scalars and static temp
                EnableSpecies();
                addIsoThermal(Temperature);
                SetScalarSchmidtNumber("mNa_1+", viscosity / (D_Na * density));
                SetScalarSchmidtNumber("mSO4_2-", viscosity / (D_SO4 * density));
                SetScalarSchmidtNumber("mBa_2+", viscosity / (D_Ba * density));
                SetScalarSchmidtNumber("mCl_1-", viscosity / (D_Cl * density));
                for (int i = 0; i < scalars.length - 1; i++) { // -1 becuse excluding the solid barite
                        SetScalarTurbulentSchmidtNumber(scalars[i], TurbulentSchmidtNumber);
                }

                SetScalarInitialConcentration(0, concentrationNaSO4 * 1 * 1.0);
                SetScalarInitialConcentration(1, concentrationNaSO4 * 2 * 1.0);
                SetScalarInitialConcentration(2, concentrationBaCl2 * 1 * 0.0);
                SetScalarInitialConcentration(3, concentrationBaCl2 * 2 * 0.0);
                InitializeSolution();

                // Reactive part
                CreateSpeciesFieldFunctions();
                AddUserLib(SessionDirectory + "/libuser.so");

                // Reaction Functions
                Dimensions dimensionless = Dimensions.Builder().build();
                Dimensions reactivity = Dimensions.Builder().length(-3).time(-1).build();

                createScalarFieldFunction("Saturation Rate", "SR", "pow(10,${UserPitzerSaturationIndex})",
                                dimensionless);

                createScalarFieldFunction("Reaction Parameter", "ReactionParameter",
                                "${mBa_2+} * ${mSO4_2-}", reactivity);

                                

                createVolumeAverageReport("Average Reaction Parameter", "ReactionParameter", "Fluid");
                createVolumeAverageReport("Average mSO4_2-", "mSO4_2-", "Fluid");
                createVolumeAverageReport("Average mBa_2+", "mBa_2+", "Fluid");
                

                recordAndPlotReport("Average Reaction Parameter");
                recordAndPlotReport("Average mSO4_2-");
                recordAndPlotReport("Average mBa_2+");

                if (isReactive) {
                        enableReaction();
                }

                // Effluent tracking
                for (int i = 0; i < scalars.length; i++) {
                        RecordOutletConcentrations(scalars[i]);
                }
                RecordOutletConcentrations("SR");
                setOutletBackflowConcentration(scalars);

                // Save and prepare
                SetAutoSave();
                SimName = getFileName();
                saveAs(SimName);

                // Freeze Flow and Run
                freezeFlow();
                freezeRst();
                RunTime(100000);

        }

        public void enableReaction() {

                Dimensions dimensionless = Dimensions.Builder().build();

                CreateGlobalDimensionlessParameter("R_fit_pA", "" + R_fit_pA);
                CreateGlobalDimensionlessParameter("R_fit_pB", "" + R_fit_pB);
                CreateGlobalDimensionlessParameter("k1", "" + k1);
                createScalarFieldFunction("isBulk", "isBulk", "${WallDistance} > 0.0001 ? 1 : 0 ", dimensionless);

                createScalarFieldFunction("Bulk Reaction Rate Constant", "K_bulk",
                                "${R_fit_pA}*pow(${SR},2) + ${R_fit_pB} * ${SR}", dimensionless);
                createScalarFieldFunction("Bulk Reaction Rate", "R_Bulk",
                                "${K_bulk}*${mBa_2+}*${mSO4_2-}*max(0,${SR}-1)*${isBulk}", dimensionless);
                createScalarFieldFunction("Bulk R_Ba", "R_Ba_Bulk", "-${R_Bulk}", dimensionless);
                createScalarFieldFunction("Bulk R_SO4", "R_SO4_Bulk", "-${R_Bulk}", dimensionless);

                createScalarFieldFunction("Bulk dRdmBa", "dRdmBa_Bulk",
                                "-0.5*${mBa_2+}*${K_bulk}*pow(${UserPitzerActivityCoefficient},2)/pow(10,-9.87)",
                                dimensionless);
                createScalarFieldFunction("Bulk dRdmSO4", "dRdmSO4_Bulk",
                                "-0.5*${mSO4_2-}*${K_bulk}*pow(${UserPitzerActivityCoefficient},2)/pow(10,-9.87)",
                                dimensionless);
                createScalarFieldFunction("Bulk dRdmBaSO4", "dRdmBaSO4_Bulk", "${dRdmSO4_Bulk} + ${dRdmBa_Bulk}",
                                dimensionless);

                createScalarFieldFunction("Wall Reaction Rate", "R_Wall",
                                "${k1}*${mBa_2+}*${mSO4_2-}*max(0,${SR}-1)*(1-${isBulk})", dimensionless);
                createScalarFieldFunction("Wall R_Ba", "R_Ba_Wall", "-${R_Wall}", dimensionless);
                createScalarFieldFunction("Wall R_SO4", "R_SO4_Wall", "-${R_Wall}", dimensionless);

                createScalarFieldFunction("Wall dRdmBa", "dRdmBa_Wall",
                                "-0.5*${mBa_2+}*${k1}*pow(${UserPitzerActivityCoefficient},2)/pow(10,-9.87)",
                                dimensionless);
                createScalarFieldFunction("Wall dRdmSO4", "dRdmSO4_Wall",
                                "-0.5*${mSO4_2-}*${k1}*pow(${UserPitzerActivityCoefficient},2)/pow(10,-9.87)",
                                dimensionless);

                SetPassiveScalarWallFlux(0, "R_SO4_Wall", "dRdmSO4_Wall");
                SetPassiveScalarWallFlux(2, "R_Ba_Wall", "dRdmBa_Wall");
                SetPassiveScalarWallMassSource(0, "R_SO4_Bulk", "dRdmSO4_Bulk");
                SetPassiveScalarWallMassSource(2, "R_Ba_Bulk", "dRdmBa_Bulk");
                SetPassiveScalarWallMassSource(4, "R_Bulk", "dRdmBaSO4_Bulk");
        }

        private void RecordOutletConcentrations(String Scalar) {

                Simulation simulation = getActiveSimulation();

                MassFlowAverageReport massFlowAverageReport = simulation.getReportManager()
                                .createReport(MassFlowAverageReport.class);

                FieldFunction primitiveFieldFunction = simulation.getFieldFunctionManager().getFunction(Scalar);

                massFlowAverageReport.setFieldFunction(primitiveFieldFunction);

                massFlowAverageReport.setPresentationName("Mass Flow Averaged Outlet Concentration of " + Scalar);

                Region region = simulation.getRegionManager().getRegion("Fluid");
                Boundary boundary = region.getBoundaryManager().getBoundary("OutletA");
                massFlowAverageReport.getParts().setObjects(boundary);

                simulation.getMonitorManager().createMonitorAndPlot(
                                new NeoObjectVector(new Object[] { massFlowAverageReport }), true, "%1$s Plot");

                ReportMonitor reportMonitor = ((ReportMonitor) simulation.getMonitorManager()
                                .getMonitor("Mass Flow Averaged Outlet Concentration of " + Scalar + " Monitor"));

                MonitorPlot monitorPlot_1 = simulation.getPlotManager().createMonitorPlot(
                                new NeoObjectVector(new Object[] { reportMonitor }),
                                "Mass Flow Averaged Outlet Concentration of " + Scalar + " Monitor Plot");
        }

        private void recordAndPlotReport(String reportName) {

                Simulation simulation = getActiveSimulation();
                ReportManager reportManager = simulation.getReportManager();
                Report report = reportManager.getReport​(reportName);

                simulation.getMonitorManager().createMonitorAndPlot(new NeoObjectVector(new Object[] { report }), true,
                                "%1$s Plot");

                ReportMonitor reportMonitor = ((ReportMonitor) simulation.getMonitorManager()
                                .getMonitor(reportName + " Monitor"));

                MonitorPlot monitorPlot = simulation.getPlotManager().createMonitorPlot(
                                new NeoObjectVector(new Object[] { reportMonitor }), reportName + " Time Series");
        }

        private void freezeFlow() {

                Simulation simulation = getActiveSimulation();

                SegregatedFlowSolver segregatedFlowSolver = ((SegregatedFlowSolver) simulation.getSolverManager()
                                .getSolver(SegregatedFlowSolver.class));

                segregatedFlowSolver.setFreezeFlow(true);
        }

        private void InitializeSolution() {
                Simulation simulation = getActiveSimulation();

                Solution solution = simulation.getSolution();

                solution.initializeSolution();
                solution.initializeSolution();
        }

        private void freezeRst() {

                Simulation simulation = getActiveSimulation();

                EbRsTurbSolver ebRsTurbSolver = ((EbRsTurbSolver) simulation.getSolverManager()
                                .getSolver(EbRsTurbSolver.class));

                ebRsTurbSolver.setFrozen(true);
        }

        private void createVolumeAverageReport(String reportName, String targetFieldFunction, String regionName) {
                Simulation simulation = getActiveSimulation();
                VolumeAverageReport volumeAverageReport = simulation.getReportManager()
                                .createReport(VolumeAverageReport.class);
                volumeAverageReport.setPresentationName(reportName);
                FieldFunction fieldFunctionHandle = simulation.getFieldFunctionManager()
                                .getFunction(targetFieldFunction);
                volumeAverageReport.setFieldFunction(fieldFunctionHandle);
                Region region = simulation.getRegionManager().getRegion(regionName);
                volumeAverageReport.getParts().setObjects(region);
        }

        private void createScalarFieldFunction(String name, String identifier, String definition, Dimensions units) {
                Simulation simulation = getActiveSimulation();
                UserFieldFunction userFieldFunction = simulation.getFieldFunctionManager().createFieldFunction();
                userFieldFunction.getTypeOption().setSelected(FieldFunctionTypeOption.Type.SCALAR);
                userFieldFunction.setPresentationName(name);
                userFieldFunction.setFunctionName(identifier);
                userFieldFunction.setDimensions(units);
                userFieldFunction.setDefinition(definition);
        }

        private void EnableAdaptiveTimeStepping(double getTargetMeanCfl, double getTargetMaxCfl) {

                Simulation simulation = getActiveSimulation();
                PhysicsContinuum physicsContinuum = ((PhysicsContinuum) simulation.getContinuumManager()
                                .getContinuum("Physics 1"));
                physicsContinuum.enable(AdaptiveTimeStepModel.class);

                AdaptiveTimeStepModel adaptiveTimeStepModel = physicsContinuum.getModelManager()
                                .getModel(AdaptiveTimeStepModel.class);
                ConvectiveCflTimeStepProvider convectiveCflTimeStepProvider = adaptiveTimeStepModel
                                .getTimeStepProviderManager().createObject(ConvectiveCflTimeStepProvider.class);

                convectiveCflTimeStepProvider.getTargetMeanCfl().setValue(getTargetMeanCfl);
                convectiveCflTimeStepProvider.getTargetMaxCfl().setValue(getTargetMaxCfl);
        }

        private void setOutletBackflowConcentration(String[] scalars) {

                Simulation simulation = getActiveSimulation();
                Region region = simulation.getRegionManager().getRegion("Fluid");
                Boundary boundary = region.getBoundaryManager().getBoundary("OutletA");

                PassiveScalarProfile passiveScalarProfile = boundary.getValues().get(PassiveScalarProfile.class);
                passiveScalarProfile.setMethod(CompositeArrayProfileMethod.class);

                for (int i = 0; i < scalars.length; i++) {
                        ScalarProfile scalarProfile_i = passiveScalarProfile
                                        .getMethod(CompositeArrayProfileMethod.class).getProfile(i);
                        scalarProfile_i.getMethod(ConstantScalarProfileMethod.class).getQuantity().setDefinition(
                                        "${MassFlowAveragedOutletConcentrationof" + scalars[i] + "Report}");
                }

        }

        private void SetScalarInitialConcentration(int no, double concentration) {
                Simulation simulation = getActiveSimulation();
                PhysicsContinuum physicsContinuum = ((PhysicsContinuum) simulation.getContinuumManager()
                                .getContinuum("Physics 1"));
                PassiveScalarProfile passiveScalarProfile = physicsContinuum.getInitialConditions()
                                .get(PassiveScalarProfile.class);
                passiveScalarProfile.setMethod(CompositeArrayProfileMethod.class);
                ScalarProfile scalarProfile = passiveScalarProfile.getMethod(CompositeArrayProfileMethod.class)
                                .getProfile(no);
                scalarProfile.getMethod(ConstantScalarProfileMethod.class).getQuantity().setValue(concentration);
        }

        private void SetWallConcentration(int no, String FieldFunction) {

                Simulation simulation = getActiveSimulation();

                Region region = simulation.getRegionManager().getRegion("Fluid");

                Boundary boundary = region.getBoundaryManager().getBoundary("Stator");

                boundary.getConditions().get(WallPassiveScalarOption.class)
                                .setSelected(WallPassiveScalarOption.Type.SPECIFIED_SCALAR);

                PassiveScalarProfile passiveScalarProfile = boundary.getValues().get(PassiveScalarProfile.class);

                passiveScalarProfile.setMethod(CompositeArrayProfileMethod.class);

                ScalarProfile scalarProfile = passiveScalarProfile.getMethod(CompositeArrayProfileMethod.class)
                                .getProfile(0);

                scalarProfile.setMethod(FunctionScalarProfileMethod.class);

                PrimitiveFieldFunction primitiveFieldFunction = ((PrimitiveFieldFunction) simulation
                                .getFieldFunctionManager().getFunction("UserPitzerDiffModelWallmBa_2+"));

                scalarProfile.getMethod(FunctionScalarProfileMethod.class).setFieldFunction(primitiveFieldFunction);
        }

        private void SetScalarSchmidtNumber(String scalar, double Schmidt) {

                Simulation simulation = getActiveSimulation();

                PhysicsContinuum physicsContinuum = ((PhysicsContinuum) simulation.getContinuumManager()
                                .getContinuum("Physics 1"));

                PassiveScalarModel passiveScalarModel = physicsContinuum.getModelManager()
                                .getModel(PassiveScalarModel.class);

                PassiveScalarMaterial passiveScalarMaterial = ((PassiveScalarMaterial) passiveScalarModel
                                .getPassiveScalarManager().getPassiveScalarMaterial(scalar));

                SchmidtNumberDiffusivityMethod schmidtNumberDiffusivityMethod = ((SchmidtNumberDiffusivityMethod) passiveScalarMaterial
                                .getMaterialProperties().getMaterialProperty(PassiveScalarDiffusivityProperty.class)
                                .getMethod());

                schmidtNumberDiffusivityMethod.setSchmidtNumber(Schmidt);
        }

        private void SetScalarTurbulentSchmidtNumber(String scalar, double Schmidt) {

                Simulation simulation = getActiveSimulation();

                PhysicsContinuum physicsContinuum = ((PhysicsContinuum) simulation.getContinuumManager()
                                .getContinuum("Physics 1"));

                PassiveScalarModel passiveScalarModel = physicsContinuum.getModelManager()
                                .getModel(PassiveScalarModel.class);

                PassiveScalarMaterial passiveScalarMaterial = ((PassiveScalarMaterial) passiveScalarModel
                                .getPassiveScalarManager().getPassiveScalarMaterial(scalar));

                ConstantMaterialPropertyMethod constantMaterialPropertyMethod = ((ConstantMaterialPropertyMethod) passiveScalarMaterial
                                .getMaterialProperties()
                                .getMaterialProperty(PassiveScalarTurbulentSchmidtNumberProperty.class).getMethod());

                constantMaterialPropertyMethod.getQuantity().setValue(Schmidt);
        }

        private void ExportAllMonitors() {
                Simulation simulation = getActiveSimulation();

                try {
                        String SessionDirectory = simulation.getSessionDir();
                        Files.createDirectories(Paths.get(SessionDirectory + "/monitors"));

                        Collection<Monitor> monitors = simulation.getMonitorManager().getMonitors();

                        for (Monitor monitor : monitors) {
                                monitor.export(SessionDirectory + "/monitors/"
                                                + monitor.getPresentationName().replace(" ", "") + ".csv");
                        }
                } catch (Exception ex) {
                        simulation.println(ex);
                }

        }

        private void SwapToLes() {

                Simulation simulation = getActiveSimulation();
                PhysicsContinuum physicsContinuum = ((PhysicsContinuum) simulation.getContinuumManager()
                                .getContinuum("Physics 1"));

                if (IsSteady) {
                        SteadyModel steadyModel_0 = physicsContinuum.getModelManager().getModel(SteadyModel.class);
                        physicsContinuum.disableModel(steadyModel_0);
                        physicsContinuum.enable(ImplicitUnsteadyModel.class);
                        IsSteady = false;
                }

                ImplicitUnsteadySolver implicitUnsteadySolver = ((ImplicitUnsteadySolver) simulation.getSolverManager()
                                .getSolver(ImplicitUnsteadySolver.class));

                implicitUnsteadySolver.getTimeStep().setValue(TimeStep);

                if (TurbulenceModel == "RANS-kOmega") {
                        physicsContinuum.disableModel(
                                        physicsContinuum.getModelManager().getModel(TurbulentModel.class));
                        physicsContinuum.disableModel(
                                        physicsContinuum.getModelManager().getModel(RansTurbulenceModel.class));
                        physicsContinuum.disableModel(
                                        physicsContinuum.getModelManager().getModel(KOmegaTurbulence.class));
                        physicsContinuum.disableModel(
                                        physicsContinuum.getModelManager().getModel(SstKwTurbModel.class));
                        physicsContinuum.disableModel(
                                        physicsContinuum.getModelManager().getModel(KwAllYplusWallTreatment.class));
                        physicsContinuum.disableModel(
                                        physicsContinuum.getModelManager().getModel(GammaTransitionModel.class));

                } else if (TurbulenceModel == "RANS-RST") {
                        physicsContinuum.disableModel(
                                        physicsContinuum.getModelManager().getModel(RansTurbulenceModel.class));
                        physicsContinuum.disableModel(
                                        physicsContinuum.getModelManager().getModel(ReynoldsStressTurbulence.class));
                        physicsContinuum.disableModel(physicsContinuum.getModelManager().getModel(EbRsTurbModel.class));
                        physicsContinuum.disableModel(
                                        physicsContinuum.getModelManager().getModel(EbRsAllYplusWallTreatment.class));

                } else if (TurbulenceModel == "RANS-kEpsilon") {
                        physicsContinuum.disableModel(
                                        physicsContinuum.getModelManager().getModel(RansTurbulenceModel.class));
                        physicsContinuum.disableModel(
                                        physicsContinuum.getModelManager().getModel(KEpsilonTurbulence.class));
                        physicsContinuum.disableModel(
                                        physicsContinuum.getModelManager().getModel(RkeTwoLayerTurbModel.class));
                        physicsContinuum.disableModel(physicsContinuum.getModelManager()
                                        .getModel(KeTwoLayerAllYplusWallTreatment.class));
                } else if (TurbulenceModel == "LES") {

                } else {
                        physicsContinuum.disableModel(physicsContinuum.getModelManager().getModel(LaminarModel.class));
                }

                TurbulenceModel = "LES";
                physicsContinuum.enable(TurbulentModel.class);
                physicsContinuum.enable(LesTurbulenceModel.class);
                physicsContinuum.enable(WaleSgsModel.class);
                physicsContinuum.enable(LesAllYplusWallTreatment.class);

                setLesCw(0.325);
        }

        private void setLesCw(double Cw) {
                Simulation simulation = getActiveSimulation();
                PhysicsContinuum physicsContinuum = ((PhysicsContinuum) simulation.getContinuumManager()
                                .getContinuum("Physics 1"));
                WaleSgsModel waleSgsModel = physicsContinuum.getModelManager().getModel(WaleSgsModel.class);
                waleSgsModel.setCw(Cw);
        }

        private void CreateSpeciesFieldFunctions() {

                CreateGlobalDimensionlessParameter("MolarMassWater", "" + molarMassH2O);
                CreateGlobalDimensionlessParameter("MolarMassBarite", "" + molarMassBarite);
                CreateGlobalDimensionlessParameter("DensityBarite", "" + DensityBarite);

                createDimensionlessFieldFuncs("mEtc_1-", "${mCl_1-} + ${mNa_1+}");
                createDimensionlessFieldFuncs("mEtc_2-", "${mSO4_2-} + ${mBa_2+}");

                createDimensionlessFieldFuncs("mTot", "1.0 / ${MolarMassWater} + ${mEtc_1-} + ${mEtc_2-}");

                createDimensionlessFieldFuncs("yEtc_1-", "${mEtc_1-} / ${mTot}");
                createDimensionlessFieldFuncs("yEtc_2-", "${mEtc_2-} / ${mTot}");

                for (int i = 0; i < scalars.length; i++) {
                        String mName = scalars[i];
                        String yName = mName.replaceFirst("m", "y");
                        String definition = "${" + mName + "} / ${mTot}";
                        createDimensionlessFieldFuncs(yName, definition);
                }

                createDimensionlessFieldFuncs("dBaSO4dt", "${UserDebyeHuckelWallDeposition(Molality)} * ${Density}");
                createDimensionlessFieldFuncs("dydt", "-${UserDebyeHuckelWallDeposition(MoleFraction)} / ${Volume}");
                createDimensionlessFieldFuncs("dBadt", "-${dBaSO4dt}");
                createDimensionlessFieldFuncs("dSO4dt", "-${dBaSO4dt}");

                createDimensionlessFieldFuncs("BariteVolumeFraction",
                                "${BariteScale} * ${Density} * ${MolarMassBarite} / ${DensityBarite}");

        }

        private void EnableSpecies() {
                // Totals:

                // Inlet B280
                double mNa2SO4_inletA = concentrationNaSO4;
                double nu_Na = 2;
                double nu_SO4 = 1;
                double nu_Na2SO4 = 1;
                double mNa_inletA = (nu_Na / nu_Na2SO4) * mNa2SO4_inletA;
                double mSO4_inletA = (nu_SO4 / nu_Na2SO4) * mNa2SO4_inletA;

                // Inlet B
                double mBaCl2_inletB = concentrationBaCl2;
                double nu_Cl = 2;
                double nu_Ba = 1;
                double nu_BaCl2 = 1;
                double mBa_inletB = (nu_Ba / nu_BaCl2) * mBaCl2_inletB;
                double mCl_inletB = (nu_Cl / nu_BaCl2) * mBaCl2_inletB;

                double[] inletAConcentrations = { mSO4_inletA, mNa_inletA, 0.0, 0.0, 0.0 };
                double[] inletBConcentrations = { 0.0, 0.0, mBa_inletB, mCl_inletB, 0.0 };

                boolean[] convectionOnly = { false, false, false, false, true };

                AddPassiveScalar(scalars, convectionOnly);
                SetPassiveScalarInlet("InletA", inletAConcentrations);
                SetPassiveScalarInlet("InletB", inletBConcentrations);
        }

        private void RunSimulation() {

                Simulation simulation = getActiveSimulation();
                simulation.getSimulationIterator().run();
        }

        private void RunTime(double time) {
                Simulation simulation = getActiveSimulation();

                PhysicalTimeStoppingCriterion physicalTimeStoppingCriterion = ((PhysicalTimeStoppingCriterion) simulation
                                .getSolverStoppingCriterionManager()
                                .getSolverStoppingCriterion("Maximum Physical Time"));

                double curTime = simulation.getSolution().getPhysicalTime();
                physicalTimeStoppingCriterion.getMaximumTime().setValue(curTime + time);

                RunSimulation();
        }

        private void setInnerIterationCount(int count) {
                Simulation simulation = getActiveSimulation();
                InnerIterationStoppingCriterion innerIterationStoppingCriterion = ((InnerIterationStoppingCriterion) simulation
                                .getSolverStoppingCriterionManager()
                                .getSolverStoppingCriterion("Maximum Inner Iterations"));
                innerIterationStoppingCriterion.setMaximumNumberInnerIterations(count);
        }

        private void enableSecondOrderTimestep() {
                Simulation simulation = getActiveSimulation();
                ImplicitUnsteadySolver implicitUnsteadySolver = ((ImplicitUnsteadySolver) simulation.getSolverManager()
                                .getSolver(ImplicitUnsteadySolver.class));
                implicitUnsteadySolver.getTimeDiscretizationOption()
                                .setSelected(TimeDiscretizationOption.Type.SECOND_ORDER);
        }

        private void AddUserLib(String libPath) {
                Simulation simulation = getActiveSimulation();
                UserLibrary userLibrary = simulation.getUserFunctionManager().createUserLibrary(resolvePath(libPath));
        }

        private void addIsoThermal(double TempC) {

                Simulation simulation = getActiveSimulation();

                PhysicsContinuum physicsContinuum = ((PhysicsContinuum) simulation.getContinuumManager()
                                .getContinuum("Physics 1"));

                physicsContinuum.enable(SegregatedFluidIsothermalModel.class);

                SegregatedFluidIsothermalModel segregatedFluidIsothermalModel_0 = physicsContinuum.getModelManager()
                                .getModel(SegregatedFluidIsothermalModel.class);

                Units units_0 = ((Units) simulation.getUnitsManager().getObject("C"));

                segregatedFluidIsothermalModel_0.getContinuumTemperature().setUnits(units_0);

                segregatedFluidIsothermalModel_0.getContinuumTemperature().setValue(TempC);
        }

        private void AddPassiveScalar(String[] scalars, boolean[] convectionOnly) {

                Simulation simulation = getActiveSimulation();
                PhysicsContinuum physicsContinuum = ((PhysicsContinuum) simulation.getContinuumManager()
                                .getContinuum("Physics 1"));
                physicsContinuum.enable(PassiveScalarModel.class);

                for (int i = 0; i < scalars.length; i++) {
                        String scalar = scalars[i];
                        PassiveScalarModel passiveScalarModel = physicsContinuum.getModelManager()
                                        .getModel(PassiveScalarModel.class);

                        PassiveScalarMaterial passiveScalarMaterial = passiveScalarModel.getPassiveScalarManager()
                                        .createPassiveScalarMaterial(PassiveScalarMaterial.class);

                        if (convectionOnly[i]) {
                                passiveScalarMaterial.getTransportOption()
                                                .setSelected(PassiveScalarTransportOption.Type.CONVECTION_ONLY);
                        }
                        passiveScalarMaterial.getClipMode().setSelected(PassiveScalarClipMode.Type.CLIP_BOTH);

                        passiveScalarMaterial.setMaxAllowable(1.0);

                        passiveScalarMaterial.setPresentationName(scalar);

                        if (TurbulenceModel == "RANS-RST" && !convectionOnly[i]) {
                                passiveScalarMaterial.setUseGGDH(true);
                        }
                }
        }

        private void SetPassiveScalarInlet(String inletName, double[] inletConcentrations) {

                Simulation simulation = getActiveSimulation();

                Region region = simulation.getRegionManager().getRegion("Fluid");

                Boundary boundary = region.getBoundaryManager().getBoundary(inletName);

                PassiveScalarProfile passiveScalarProfile = boundary.getValues().get(PassiveScalarProfile.class);

                passiveScalarProfile.setMethod(CompositeArrayProfileMethod.class);

                for (int i = 0; i < inletConcentrations.length; i++) {
                        ScalarProfile scalarProfile_0 = passiveScalarProfile
                                        .getMethod(CompositeArrayProfileMethod.class).getProfile(i);
                        scalarProfile_0.getMethod(ConstantScalarProfileMethod.class).getQuantity()
                                        .setValue(inletConcentrations[i]);

                }

        }

        private void createDimensionlessFieldFuncs(String name, String definition) {

                Simulation simulation = getActiveSimulation();

                UserFieldFunction userFieldFunction = simulation.getFieldFunctionManager().createFieldFunction();

                userFieldFunction.getTypeOption().setSelected(FieldFunctionTypeOption.Type.SCALAR);

                userFieldFunction.setPresentationName(name);
                userFieldFunction.setFunctionName(name);
                userFieldFunction.setDefinition(definition);

        }

        private void SetNormalStressOption(boolean activated) {

                if (TurbulenceModel == "RANS-kOmega") {
                        Simulation simulation = getActiveSimulation();

                        PhysicsContinuum physicsContinuum = ((PhysicsContinuum) simulation.getContinuumManager()
                                        .getContinuum("Physics 1"));

                        SstKwTurbModel sstKwTurbModel = physicsContinuum.getModelManager()
                                        .getModel(SstKwTurbModel.class);

                        sstKwTurbModel.setNormalStressOption(activated);
                }
        }

        private void AddSurfaceSideViewScene(String name, String fieldFunction, double Min, double Max) {

                Simulation simulation = getActiveSimulation();

                simulation.getSceneManager().createScalarScene("Scalar Scene", "Outline", "Scalar");
                Scene scene_0 = simulation.getSceneManager().getScene("Scalar Scene 1");
                scene_0.initializeAndWait();

                PartDisplayer partDisplayer_0 = ((PartDisplayer) scene_0.getDisplayerManager()
                                .getDisplayer("Outline 1"));
                partDisplayer_0.initialize();

                ScalarDisplayer scalarDisplayer_0 = ((ScalarDisplayer) scene_0.getDisplayerManager()
                                .getDisplayer("Scalar 1"));
                scalarDisplayer_0.initialize();

                Legend legend_0 = scalarDisplayer_0.getLegend();
                BlueRedLookupTable blueRedLookupTable_0 = ((BlueRedLookupTable) simulation.get(LookupTableManager.class)
                                .getObject("blue-red"));
                legend_0.setLookupTable(blueRedLookupTable_0);

                SceneUpdate sceneUpdate_0 = scene_0.getSceneUpdate();
                HardcopyProperties hardcopyProperties_0 = sceneUpdate_0.getHardcopyProperties();
                hardcopyProperties_0.setCurrentResolutionWidth(25);
                hardcopyProperties_0.setCurrentResolutionHeight(25);
                hardcopyProperties_0.setCurrentResolutionWidth(1024);
                hardcopyProperties_0.setCurrentResolutionHeight(494);

                scene_0.resetCamera();
                CurrentView currentView_0 = scene_0.getCurrentView();
                currentView_0.setInput(new DoubleVector(
                                new double[] { 1.552230566304047E-14, -8.213661445433118E-9, 0.07000000000000002 }),
                                new DoubleVector(new double[] { 1.552230566304047E-14, -8.213661445433118E-9,
                                                0.47714309349357603 }),
                                new DoubleVector(new double[] { 0.0, 1.0, 0.0 }), 0.10628567536481787, 1, 30.0);

                scene_0.setViewOrientation(new DoubleVector(new double[] { 0.0, 1.0, 0.0 }),
                                new DoubleVector(new double[] { 0.0, 0.0, 1.0 }));
                scene_0.close();

                scene_0.setPresentationName(name);
                scene_0.setBackgroundColorMode(BackgroundColorMode.SOLID);
                LogoAnnotation logoAnnotation_0 = ((LogoAnnotation) simulation.getAnnotationManager()
                                .getObject("Logo"));
                scene_0.getAnnotationPropManager().removePropsForAnnotations(logoAnnotation_0);
                PhysicalTimeAnnotation physicalTimeAnnotation_0 = ((PhysicalTimeAnnotation) simulation
                                .getAnnotationManager().getObject("Solution Time"));
                PhysicalTimeAnnotationProp physicalTimeAnnotationProp_0 = (PhysicalTimeAnnotationProp) scene_0
                                .getAnnotationPropManager().createPropForAnnotation(physicalTimeAnnotation_0);

                sceneUpdate_0.getUpdateModeOption().setSelected(StarUpdateModeOption.Type.DELTATIME);
                DeltaTimeUpdateFrequency deltaTimeUpdateFrequency_0 = sceneUpdate_0.getDeltaTimeUpdateFrequency();
                Units units_0 = ((Units) simulation.getUnitsManager().getObject("s"));
                deltaTimeUpdateFrequency_0.setDeltaTime("0.02", units_0); // 50 FPS
                sceneUpdate_0.setAnimationFilenameBase(name.replace(' ', '_'));
                sceneUpdate_0.setAnimationFilePath(uid + "_" + name.replace(' ', '_'));
                sceneUpdate_0.setSaveAnimation(true);

                scalarDisplayer_0.getInputParts().setQuery(null);
                Region region_0 = simulation.getRegionManager().getRegion("Fluid");
                Boundary boundary_0 = region_0.getBoundaryManager().getBoundary("InletA");
                Boundary boundary_1 = region_0.getBoundaryManager().getBoundary("InletB");
                Boundary boundary_2 = region_0.getBoundaryManager().getBoundary("OutletA");
                Boundary boundary_3 = region_0.getBoundaryManager().getBoundary("OutletB");
                Boundary boundary_4 = region_0.getBoundaryManager().getBoundary("Rotor");
                Boundary boundary_5 = region_0.getBoundaryManager().getBoundary("Stator");
                scalarDisplayer_0.getInputParts().setObjects(boundary_0, boundary_1, boundary_2, boundary_3, boundary_4,
                                boundary_5);

                FieldFunction primitiveFieldFunction_3 = ((FieldFunction) simulation.getFieldFunctionManager()
                                .getFunction(fieldFunction));
                scalarDisplayer_0.getScalarDisplayQuantity().setFieldFunction(primitiveFieldFunction_3);
                scalarDisplayer_0.getScalarDisplayQuantity().setAutoRange(AutoRangeMode.NONE);
                scalarDisplayer_0.getScalarDisplayQuantity().setRange(new DoubleVector(new double[] { Min, Max }));
                scalarDisplayer_0.getScalarDisplayQuantity().setClip(ClipMode.NONE);
        }

        private void AddSideViewScene(String name, String fieldFunction, double Min, double Max) {

                Simulation simulation = getActiveSimulation();

                simulation.getSceneManager().createScalarScene("Scalar Scene", "Outline", "Scalar");

                Scene scene_0 = simulation.getSceneManager().getScene("Scalar Scene 1");

                scene_0.initializeAndWait();

                PartDisplayer partDisplayer_0 = ((PartDisplayer) scene_0.getDisplayerManager()
                                .getDisplayer("Outline 1"));

                partDisplayer_0.initialize();

                ScalarDisplayer scalarDisplayer_0 = ((ScalarDisplayer) scene_0.getDisplayerManager()
                                .getDisplayer("Scalar 1"));

                scalarDisplayer_0.initialize();

                Legend legend_0 = scalarDisplayer_0.getLegend();

                BlueRedLookupTable blueRedLookupTable_0 = ((BlueRedLookupTable) simulation.get(LookupTableManager.class)
                                .getObject("blue-red"));

                legend_0.setLookupTable(blueRedLookupTable_0);

                SceneUpdate sceneUpdate_0 = scene_0.getSceneUpdate();

                HardcopyProperties hardcopyProperties_0 = sceneUpdate_0.getHardcopyProperties();

                hardcopyProperties_0.setCurrentResolutionWidth(25);

                hardcopyProperties_0.setCurrentResolutionHeight(25);

                hardcopyProperties_0.setCurrentResolutionWidth(1024);

                hardcopyProperties_0.setCurrentResolutionHeight(494);

                scene_0.resetCamera();

                CurrentView currentView_0 = scene_0.getCurrentView();

                currentView_0.setInput(new DoubleVector(
                                new double[] { 1.552230566304047E-14, -8.213661445433118E-9, 0.07000000000000002 }),
                                new DoubleVector(new double[] { 1.552230566304047E-14, -8.213661445433118E-9,
                                                0.47714309349357603 }),
                                new DoubleVector(new double[] { 0.0, 1.0, 0.0 }), 0.10628567536481787, 1, 30.0);

                scene_0.setViewOrientation(new DoubleVector(new double[] { 0.0, 1.0, 0.0 }),
                                new DoubleVector(new double[] { 0.0, 0.0, 1.0 }));

                scene_0.close();

                scene_0.setPresentationName(name);

                scene_0.setBackgroundColorMode(BackgroundColorMode.SOLID);

                LogoAnnotation logoAnnotation_0 = ((LogoAnnotation) simulation.getAnnotationManager()
                                .getObject("Logo"));

                scene_0.getAnnotationPropManager().removePropsForAnnotations(logoAnnotation_0);

                PhysicalTimeAnnotation physicalTimeAnnotation_0 = ((PhysicalTimeAnnotation) simulation
                                .getAnnotationManager().getObject("Solution Time"));

                PhysicalTimeAnnotationProp physicalTimeAnnotationProp_0 = (PhysicalTimeAnnotationProp) scene_0
                                .getAnnotationPropManager().createPropForAnnotation(physicalTimeAnnotation_0);

                sceneUpdate_0.getUpdateModeOption().setSelected(StarUpdateModeOption.Type.DELTATIME);

                DeltaTimeUpdateFrequency deltaTimeUpdateFrequency_0 = sceneUpdate_0.getDeltaTimeUpdateFrequency();
                Units units_0 = ((Units) simulation.getUnitsManager().getObject("s"));
                deltaTimeUpdateFrequency_0.setDeltaTime("0.02", units_0); // 50 FPS

                sceneUpdate_0.setAnimationFilenameBase(name.replace(' ', '_'));
                sceneUpdate_0.setAnimationFilePath(uid + "_" + name.replace(' ', '_'));
                sceneUpdate_0.setSaveAnimation(true);

                scalarDisplayer_0.getInputParts().setQuery(null);
                PlaneSection planeSection_0 = ((PlaneSection) simulation.getPartManager().getObject("XY Plane"));
                PlaneSection planeSection_1 = ((PlaneSection) simulation.getPartManager().getObject("XZ Plane"));
                PlaneSection planeSection_2 = ((PlaneSection) simulation.getPartManager().getObject("YZ Plane"));
                scalarDisplayer_0.getInputParts().setObjects(planeSection_0, planeSection_1, planeSection_2);

                FieldFunction primitiveFieldFunction_3 = ((FieldFunction) simulation.getFieldFunctionManager()
                                .getFunction(fieldFunction));
                scalarDisplayer_0.getScalarDisplayQuantity().setFieldFunction(primitiveFieldFunction_3);
                scalarDisplayer_0.getScalarDisplayQuantity().setAutoRange(AutoRangeMode.NONE);
                scalarDisplayer_0.getScalarDisplayQuantity().setRange(new DoubleVector(new double[] { Min, Max }));
                scalarDisplayer_0.getScalarDisplayQuantity().setClip(ClipMode.NONE);

        }

        private void swapToUnsteady() {

                if (IsSteady) {
                        IsSteady = false;
                        Simulation simulation = getActiveSimulation();

                        PhysicsContinuum physicsContinuum = ((PhysicsContinuum) simulation.getContinuumManager()
                                        .getContinuum("Physics 1"));
                        SteadyModel steadyModel = physicsContinuum.getModelManager().getModel(SteadyModel.class);

                        physicsContinuum.disableModel(steadyModel);
                        physicsContinuum.enable(ImplicitUnsteadyModel.class);
                }
        }

        private void setTimeStep(double deltat) {
                Simulation simulation = getActiveSimulation();

                PhysicsContinuum physicsContinuum = simulation.getContinuumManager()
                                .createContinuum(PhysicsContinuum.class);

                physicsContinuum.enable(ThreeDimensionalModel.class);

                ImplicitUnsteadySolver implicitUnsteadySolver = ((ImplicitUnsteadySolver) simulation.getSolverManager()
                                .getSolver(ImplicitUnsteadySolver.class));

                implicitUnsteadySolver.getTimeStep().setValue(deltat);

        }

        private void SetAutoSave() {

                Simulation simulation = getActiveSimulation();

                AutoSave autoSave = simulation.getSimulationIterator().getAutoSave();

                autoSave.setSeparator("_At_");
                autoSave.setFormatWidth(6);

                StarUpdate starUpdate = autoSave.getStarUpdate();

                starUpdate.setEnabled(true);
                if (IsSteady) {
                        starUpdate.getUpdateModeOption().setSelected(StarUpdateModeOption.Type.ITERATION);
                        IterationUpdateFrequency iterationUpdateFrequency = starUpdate.getIterationUpdateFrequency();
                        iterationUpdateFrequency.setIterations(5000);
                } else {
                        starUpdate.getUpdateModeOption().setSelected(StarUpdateModeOption.Type.TIMESTEP);
                        TimeStepUpdateFrequency timeStepUpdateFrequency = starUpdate.getTimeStepUpdateFrequency();
                        timeStepUpdateFrequency.setTimeSteps(1000);
                }
                autoSave.setMaxAutosavedFiles(2);
        }

        private void SetTransientStoppingCriteria(double physicalTime) {

                Simulation simulation = getActiveSimulation();

                PhysicalTimeStoppingCriterion physicalTimeStoppingCriterion = ((PhysicalTimeStoppingCriterion) simulation
                                .getSolverStoppingCriterionManager()
                                .getSolverStoppingCriterion("Maximum Physical Time"));

                physicalTimeStoppingCriterion.getMaximumTime().setValue(physicalTime);

                StepStoppingCriterion stepStoppingCriterion = ((StepStoppingCriterion) simulation
                                .getSolverStoppingCriterionManager().getSolverStoppingCriterion("Maximum Steps"));

                stepStoppingCriterion.setIsUsed(false);
        }

        private void CreateGlobalDimensionlessParameter(String name, String definition) {
                Simulation simulation = getActiveSimulation();
                ScalarGlobalParameter sParameter = (ScalarGlobalParameter) simulation.get(GlobalParameterManager.class)
                                .createGlobalParameter(ScalarGlobalParameter.class, name);

                sParameter.getQuantity().setDefinition(definition);
        }

        private void SetMassFlowInletFlowRate(String BoundaryName, String RegionName, double massFlowSi) {

                Simulation simulation = getActiveSimulation();

                Region region = simulation.getRegionManager().getRegion(RegionName);

                Boundary boundary = region.getBoundaryManager().getBoundary(BoundaryName);

                MassFlowRateProfile massFlowRateProfile = boundary.getValues().get(MassFlowRateProfile.class);

                massFlowRateProfile.getMethod(ConstantScalarProfileMethod.class).getQuantity().setValue(massFlowSi);
        }

        private void SetPassiveScalarWallFlux(int scalarInd, String fluxFieldFunc, String FluxDerivativeFieldFunc) {

                Simulation simulation = getActiveSimulation();
                Region region = simulation.getRegionManager().getRegion("Fluid");
                Boundary boundary = region.getBoundaryManager().getBoundary("Stator");

                boundary.getConditions().get(WallPassiveScalarOption.class)
                                .setSelected(WallPassiveScalarOption.Type.SPECIFIED_FLUX);
                boundary.getConditions().get(WallPassiveScalarFluxDerivativeOption.class)
                                .setSelected(WallPassiveScalarFluxDerivativeOption.Type.SPECIFIED);

                PassiveScalarFluxProfile passiveScalarFluxProfile = boundary.getValues()
                                .get(PassiveScalarFluxProfile.class);
                passiveScalarFluxProfile.setMethod(CompositeArrayProfileMethod.class);

                PassiveScalarFluxDerivativeProfile passiveScalarFluxDerivativeProfile = boundary.getValues()
                                .get(PassiveScalarFluxDerivativeProfile.class);
                passiveScalarFluxDerivativeProfile.setMethod(CompositeArrayProfileMethod.class);

                ScalarProfile scalarProfile = passiveScalarFluxProfile.getMethod(CompositeArrayProfileMethod.class)
                                .getProfile(scalarInd);
                scalarProfile.setMethod(FunctionScalarProfileMethod.class);
                UserFieldFunction fluxFieldFunction = ((UserFieldFunction) simulation.getFieldFunctionManager()
                                .getFunction(fluxFieldFunc));
                scalarProfile.getMethod(FunctionScalarProfileMethod.class).setFieldFunction(fluxFieldFunction);

                ScalarProfile scalarProfile_1 = passiveScalarFluxDerivativeProfile
                                .getMethod(CompositeArrayProfileMethod.class).getProfile(scalarInd);
                scalarProfile_1.setMethod(FunctionScalarProfileMethod.class);
                UserFieldFunction FluxDerivativeFieldFunction = ((UserFieldFunction) simulation
                                .getFieldFunctionManager().getFunction(FluxDerivativeFieldFunc));
                scalarProfile_1.getMethod(FunctionScalarProfileMethod.class)
                                .setFieldFunction(FluxDerivativeFieldFunction);
        }

        private void SetPassiveScalarWallMassSource(int scalarInd, String sourceFieldFunc,
                        String sourceDerivativeFieldFunc) {

                Simulation simulation = getActiveSimulation();

                Region region = simulation.getRegionManager().getRegion("Fluid");

                region.getConditions().get(PassiveScalarUserSourceOption.class)
                                .setSelected(PassiveScalarUserSourceOption.Type.EXPLICIT_DENSITY);

                PassiveScalarUserSource passiveScalarUserSource = region.getValues().get(PassiveScalarUserSource.class);

                passiveScalarUserSource.setMethod(CompositeArrayProfileMethod.class);

                ScalarProfile scalarProfile = passiveScalarUserSource.getMethod(CompositeArrayProfileMethod.class)
                                .getProfile(scalarInd);
                scalarProfile.setMethod(FunctionScalarProfileMethod.class);
                UserFieldFunction userFieldFunction = ((UserFieldFunction) simulation.getFieldFunctionManager()
                                .getFunction(sourceFieldFunc));
                scalarProfile.getMethod(FunctionScalarProfileMethod.class).setFieldFunction(userFieldFunction);
                PassiveScalarUserSourceDerivative passiveScalarUserSourceDerivative = region.getValues()
                                .get(PassiveScalarUserSourceDerivative.class);

                passiveScalarUserSourceDerivative.setMethod(CompositeArrayProfileMethod.class);

                ScalarProfile scalarProfile_1 = passiveScalarUserSourceDerivative
                                .getMethod(CompositeArrayProfileMethod.class).getProfile(scalarInd);
                scalarProfile_1.setMethod(FunctionScalarProfileMethod.class);
                UserFieldFunction userFieldFunction_1 = ((UserFieldFunction) simulation.getFieldFunctionManager()
                                .getFunction(sourceDerivativeFieldFunc));
                scalarProfile_1.getMethod(FunctionScalarProfileMethod.class).setFieldFunction(userFieldFunction_1);
        }
}
